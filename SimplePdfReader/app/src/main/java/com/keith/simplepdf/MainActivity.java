package com.keith.simplepdf;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.net.Uri;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
 final ArrayList<Doc> docs = new ArrayList<>();
 final ExecutorService worker = Executors.newSingleThreadExecutor();
 LinearLayout tabs; TextView status; PdfView view; Doc active;
 int generation = 0; boolean destroyed = false;
 static class Doc {
  Uri uri; String name; int page=0, count=0; float zoom=1, x=0, y=0;
  Doc(Uri u,String n) {uri=u;name=n;}
 }
 Button button(String name, LinearLayout row, View.OnClickListener action) {
  Button b=new Button(this); b.setText(name); b.setOnClickListener(action);
  b.setMinWidth(0); row.addView(b,new LinearLayout.LayoutParams(-2,-1)); return b;
 }
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout root=new LinearLayout(this); root.setOrientation(1);
  root.setPadding(0,0,0,0); setContentView(root);
  ViewCompatInsets(root);
  LinearLayout bar=new LinearLayout(this);
  root.addView(bar,new LinearLayout.LayoutParams(-1,56));
  button("Open PDF",bar,v->pick());
  button("−",bar,v->zoom(0.8f)); button("+",bar,v->zoom(1.25f));
  button("Fit",bar,v->{if(active!=null){active.zoom=1;active.x=active.y=0;view.invalidate();}});
  HorizontalScrollView strip=new HorizontalScrollView(this);
  tabs=new LinearLayout(this); strip.addView(tabs);
  root.addView(strip,new LinearLayout.LayoutParams(-1,52));
  view=new PdfView();root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
  LinearLayout nav=new LinearLayout(this);
  button("Previous",nav,v->turn(-1));
  status=new TextView(this);status.setGravity(Gravity.CENTER);
  nav.addView(status,new LinearLayout.LayoutParams(0,-1,1));
  button("Next",nav,v->turn(1)); root.addView(nav,new LinearLayout.LayoutParams(-1,56));
  if(state!=null){
   ArrayList<String> uris=state.getStringArrayList("uris");
   ArrayList<String> names=state.getStringArrayList("names");
   if(uris!=null) for(int i=0;i<uris.size();i++){
    Doc d=new Doc(Uri.parse(uris.get(i)), names.get(i));d.page=state.getInt("p"+i);
    d.zoom=state.getFloat("z"+i,1);docs.add(d);
   }
   if(!docs.isEmpty())active=docs.get(Math.min(state.getInt("selected"),docs.size()-1));
  }
  refreshTabs(); if(active!=null)render();
  else {status.setText("Open a PDF to begin");}
  if(state==null)handleIntent(getIntent());
 }
 void ViewCompatInsets(View root){
  root.setOnApplyWindowInsetsListener((v,insets)->{
   v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;
  });
 }
 void pick(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/pdf");
  i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
  i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
  startActivityForResult(i,42);}
 @Override public void onActivityResult(int r,int c,Intent data){
  super.onActivityResult(r,c,data);if(r!=42||c!=RESULT_OK||data==null)return;
  if(data.getClipData()!=null)for(int j=0;j<data.getClipData().getItemCount();j++)open(data.getClipData().getItemAt(j).getUri());
  else if(data.getData()!=null)open(data.getData());
 }
 @Override protected void onNewIntent(Intent i){super.onNewIntent(i);handleIntent(i);}
 void handleIntent(Intent i){if(Intent.ACTION_VIEW.equals(i.getAction())&&i.getData()!=null)open(i.getData());}
 void open(Uri uri){
  for(Doc d:docs)if(d.uri.equals(uri)){active=d;refreshTabs();render();return;}
  try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
  String name="PDF";
  try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
   if(c!=null&&c.moveToFirst())name=c.getString(0);
  }catch(Exception ignored){}
  active=new Doc(uri,name);docs.add(active);refreshTabs();render();
 }
 void refreshTabs(){
  tabs.removeAllViews();
  for(Doc d:docs){
   LinearLayout item=new LinearLayout(this);
   item.setBackgroundColor(d==active?0xffc7e5ff:0xffeeeeee);
   tabs.addView(item);
   Button title=button(d.name,item,v->{active=d;refreshTabs();render();});
   title.setMaxWidth((int)(240*getResources().getDisplayMetrics().density));
   title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);
   button("×",item,v->{
    docs.remove(d);if(active==d)active=docs.isEmpty()?null:docs.get(docs.size()-1);
    refreshTabs();render();
   });
  }
 }
 void zoom(float factor){if(active!=null){active.zoom=Math.max(1,Math.min(6,active.zoom*factor));view.invalidate();}}
 void turn(int delta){if(active!=null&&active.count>0){
  int p=Math.max(0,Math.min(active.count-1,active.page+delta));
  if(p!=active.page){active.page=p;active.x=active.y=0;render();}
 }}
 void render(){
  final int token=++generation;final Doc d=active;
  view.setBitmap(null);
  if(d==null){status.setText("Open a PDF to begin");return;}
  status.setText("Loading…");
  final int screen=Math.max(getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels);
  worker.execute(()->{
   Bitmap bitmap=null;String error=null;
   try(ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(d.uri,"r")){
    if(fd==null)throw new Exception("Unable to open file");
    try(PdfRenderer pdf=new PdfRenderer(fd)){
     d.count=pdf.getPageCount();if(d.count==0)throw new Exception("This PDF has no pages");
     d.page=Math.max(0,Math.min(d.page,d.count-1));
     try(PdfRenderer.Page page=pdf.openPage(d.page)){
      float scale=Math.min(3072f/Math.max(page.getWidth(),page.getHeight()),
       (screen*1.5f)/Math.max(page.getWidth(),page.getHeight()));
      int w=Math.max(1,Math.round(page.getWidth()*scale)),h=Math.max(1,Math.round(page.getHeight()*scale));
      bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
      page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
     }
    }
   }catch(Exception|OutOfMemoryError e){error="Cannot open this PDF. It may be password protected, unavailable, or too large.";}
   final Bitmap result=bitmap;final String message=error;
   runOnUiThread(()->{
    if(destroyed||token!=generation){if(result!=null)result.recycle();return;}
    view.setBitmap(result);
    status.setText(message==null?((d.page+1)+" / "+d.count):"Unable to open");
    if(message!=null)new AlertDialog.Builder(this).setTitle(d.name).setMessage(message).setPositiveButton("OK",null).show();
   });
  });
 }
 @Override protected void onSaveInstanceState(Bundle b){
  super.onSaveInstanceState(b);ArrayList<String> uris=new ArrayList<>(),names=new ArrayList<>();
  for(int i=0;i<docs.size();i++){Doc d=docs.get(i);uris.add(d.uri.toString());names.add(d.name);b.putInt("p"+i,d.page);b.putFloat("z"+i,d.zoom);}
  b.putStringArrayList("uris",uris);b.putStringArrayList("names",names);b.putInt("selected",Math.max(0,docs.indexOf(active)));
 }
 @Override protected void onDestroy(){destroyed=true;generation++;worker.shutdown();view.setBitmap(null);super.onDestroy();}
 class PdfView extends View {
  Bitmap bitmap;Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  float lastX,lastY;ScaleGestureDetector scale;GestureDetector gestures;
  PdfView(){super(MainActivity.this);setBackgroundColor(0xff42474d);
   scale=new ScaleGestureDetector(MainActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
    @Override public boolean onScale(ScaleGestureDetector detector){zoom(detector.getScaleFactor());return true;}
   });
   gestures=new GestureDetector(MainActivity.this,new GestureDetector.SimpleOnGestureListener(){
    @Override public boolean onDown(android.view.MotionEvent e){return true;}
    @Override public boolean onDoubleTap(android.view.MotionEvent e){
     if(active!=null){active.zoom=active.zoom>1?1:2;active.x=active.y=0;invalidate();}return true;
    }
   });
  }
  void setBitmap(Bitmap b){Bitmap old=bitmap;bitmap=b;if(old!=null)old.recycle();invalidate();}
  @Override protected void onDraw(Canvas canvas){
   super.onDraw(canvas);if(bitmap==null||active==null)return;
   float fit=Math.min((float)getWidth()/bitmap.getWidth(),(float)getHeight()/bitmap.getHeight());
   float w=bitmap.getWidth()*fit*active.zoom,h=bitmap.getHeight()*fit*active.zoom;
   float maxX=Math.max(0,(w-getWidth())/2),maxY=Math.max(0,(h-getHeight())/2);
   active.x=Math.max(-maxX,Math.min(maxX,active.x));active.y=Math.max(-maxY,Math.min(maxY,active.y));
   float x=(getWidth()-w)/2+active.x,y=(getHeight()-h)/2+active.y;
   canvas.drawBitmap(bitmap,null,new RectF(x,y,x+w,y+h),paint);
  }
  @Override public boolean onTouchEvent(android.view.MotionEvent e){
   scale.onTouchEvent(e);gestures.onTouchEvent(e);
   if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=e.getX();lastY=e.getY();}
   else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
    if(!scale.isInProgress()&&e.getPointerCount()==1&&active!=null){active.x+=e.getX()-lastX;active.y+=e.getY()-lastY;invalidate();}
    lastX=e.getX();lastY=e.getY();
   }return true;
  }
 }
}
