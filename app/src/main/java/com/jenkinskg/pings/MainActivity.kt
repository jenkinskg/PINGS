package com.jenkinskg.pings

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

data class Dev(
    val ip:String,
    val host:String,
    val mac:String,
    val vendor:String,
    val up:Boolean,
    val ms:Long,
    val type:String,
    var change:String=""
)

class MainActivity:Activity(){
    private lateinit var subnet:EditText
    private lateinit var filter:Spinner
    private lateinit var repeat:Spinner
    private lateinit var counts:TextView
    private lateinit var compareCounts:TextView
    private lateinit var tabHost:TabHost
    private lateinit var pingBtn:Button
    private lateinit var apBtn:Button
    private lateinit var nonApBtn:Button
    private lateinit var noPingBtn:Button

    private val lists=linkedMapOf<String,ListView>()
    private var devs=listOf<Dev>()
    private var before=listOf<Dev>()
    private var compareMode=false
    private val pool=Executors.newFixedThreadPool(32)
    private val handler=Handler(Looper.getMainLooper())
    private var repeatMs=0L

    private val repeatTask=object:Runnable{
        override fun run(){
            if(repeatMs>0){
                scan()
                handler.postDelayed(this,repeatMs)
            }
        }
    }

    override fun onCreate(b:Bundle?){
        super.onCreate(b)

        window.statusBarColor=Color.rgb(35,49,66)
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(242,245,249))
        }

        val header=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(14),dp(16),dp(12))
            setBackgroundColor(Color.WHITE)
            elevation=dp(3).toFloat()
        }
        header.addView(TextView(this).apply{
            text="PINGS"
            textSize=22f
            setTypeface(typeface,Typeface.BOLD)
            setTextColor(Color.rgb(35,49,66))
        })
        header.addView(TextView(this).apply{
            text="Network Availability Monitor"
            textSize=12f
            setTextColor(Color.rgb(105,115,126))
        })

        subnet=EditText(this).apply{
            setText("192.168.1.0/24")
            textSize=16f
            setPadding(dp(12),dp(8),dp(12),dp(8))
            background=fieldBackground()
        }

        val actionScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        val buttons=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(0,dp(8),0,dp(4))
        }
        fun actionButton(label:String,primary:Boolean=false,action:()->Unit)=Button(this).apply{
            text=label
            isAllCaps=false
            textSize=13f
            setTypeface(typeface,Typeface.BOLD)
            setTextColor(if(primary)Color.WHITE else Color.rgb(42,57,74))
            background=raisedButton(primary)
            elevation=dp(4).toFloat()
            minHeight=dp(42)
            setPadding(dp(14),0,dp(14),0)
            setOnClickListener{action()}
        }

        buttons.addView(actionButton("Current Subnet"){subnet.setText(currentSubnet())},buttonLp())
        buttons.addView(actionButton("Scan Now",true){scan()},buttonLp())
        buttons.addView(actionButton("Set Before"){
            before=devs.map{it.copy()}
            compareMode=false
            renderAll()
            Toast.makeText(this,"Baseline saved",Toast.LENGTH_SHORT).show()
        },buttonLp())
        buttons.addView(actionButton("Compare Before/After"){
            compareMode=true
            renderAll()
            tabHost.currentTabByTag="changes"
        },buttonLp())
        actionScroll.addView(buttons)

        val options=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(0,dp(4),0,dp(4))
        }
        options.addView(TextView(this).apply{text="Show";setTextColor(Color.DKGRAY)},LinearLayout.LayoutParams(0,-2,0.25f))
        filter=Spinner(this)
        filter.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("All","Pingable","No Ping"))
        options.addView(filter,LinearLayout.LayoutParams(0,-2,0.75f))
        options.addView(TextView(this).apply{text="Repeat";setTextColor(Color.DKGRAY)},LinearLayout.LayoutParams(0,-2,0.3f))
        repeat=Spinner(this)
        repeat.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,listOf("Off","30 sec","5 min"))
        options.addView(repeat,LinearLayout.LayoutParams(0,-2,0.7f))

        header.addView(subnet,LinearLayout.LayoutParams(-1,dp(48)))
        header.addView(actionScroll,LinearLayout.LayoutParams(-1,dp(58)))
        header.addView(options,LinearLayout.LayoutParams(-1,dp(50)))

        filter.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){renderAll()}
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }
        repeat.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                handler.removeCallbacks(repeatTask)
                repeatMs=when(position){1->30000L;2->300000L;else->0L}
                if(repeatMs>0)handler.postDelayed(repeatTask,repeatMs)
            }
            override fun onNothingSelected(parent:AdapterView<*>?){}
        }

        val countScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        val countRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(10),dp(8),dp(10),dp(6))
        }
        pingBtn=countButton("Pingable: 0",Color.rgb(43,125,67))
        apBtn=countButton("APs: 0",Color.rgb(43,94,154))
        nonApBtn=countButton("Non-APs: 0",Color.rgb(89,100,114))
        noPingBtn=countButton("Not Responding: 0",Color.rgb(166,55,55))
        pingBtn.setOnClickListener{filter.setSelection(1);tabHost.currentTabByTag="all"}
        apBtn.setOnClickListener{filter.setSelection(1);tabHost.currentTabByTag="aps"}
        nonApBtn.setOnClickListener{filter.setSelection(1);tabHost.currentTabByTag="nonaps"}
        noPingBtn.setOnClickListener{filter.setSelection(2);tabHost.currentTabByTag="all"}
        countRow.addView(pingBtn,buttonLp())
        countRow.addView(apBtn,buttonLp())
        countRow.addView(nonApBtn,buttonLp())
        countRow.addView(noPingBtn,buttonLp())
        countScroll.addView(countRow)

        counts=TextView(this).apply{
            setPadding(dp(14),dp(6),dp(14),dp(2))
            setTextColor(Color.rgb(42,67,101))
            setTypeface(typeface,Typeface.BOLD)
        }
        compareCounts=TextView(this).apply{
            setPadding(dp(14),0,dp(14),dp(6))
            setTextColor(Color.rgb(91,101,115))
        }

        tabHost=TabHost(this)
        val tabLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val tabScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false}
        val tabWidget=TabWidget(this).apply{id=android.R.id.tabs}
        tabScroll.addView(tabWidget,HorizontalScrollView.LayoutParams(-2,-2))
        val content=FrameLayout(this).apply{
            id=android.R.id.tabcontent
            setBackgroundColor(Color.WHITE)
        }
        tabLayout.addView(tabScroll,LinearLayout.LayoutParams(-1,dp(50)))
        tabLayout.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        tabHost.addView(tabLayout)
        tabHost.setup()

        addTab(content,"all","All Pings")
        addTab(content,"aps","APs")
        addTab(content,"nonaps","Non-APs")
        addTab(content,"pcs","PCs")
        addTab(content,"other","Other")
        addTab(content,"unknown","Unknown")
        addTab(content,"changes","Missing/Changed")
        styleTabs()

        root.addView(header)
        root.addView(countScroll,LinearLayout.LayoutParams(-1,dp(58)))
        root.addView(counts)
        root.addView(compareCounts)
        root.addView(tabHost,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    override fun onDestroy(){
        handler.removeCallbacks(repeatTask)
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun dp(v:Int):Int=(v*resources.displayMetrics.density).toInt()

    private fun buttonLp():LinearLayout.LayoutParams=
        LinearLayout.LayoutParams(-2,dp(44)).apply{setMargins(dp(4),0,dp(4),0)}

    private fun fieldBackground():GradientDrawable=GradientDrawable().apply{
        setColor(Color.WHITE)
        cornerRadius=dp(9).toFloat()
        setStroke(dp(1),Color.rgb(196,205,214))
    }

    private fun raisedButton(primary:Boolean):GradientDrawable{
        val colors=if(primary)
            intArrayOf(Color.rgb(67,125,190),Color.rgb(42,91,150))
        else
            intArrayOf(Color.WHITE,Color.rgb(226,232,239))
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors).apply{
            cornerRadius=dp(9).toFloat()
            setStroke(dp(1),if(primary)Color.rgb(36,78,128) else Color.rgb(181,191,202))
        }
    }

    private fun countButton(label:String,color:Int)=Button(this).apply{
        text=label
        isAllCaps=false
        textSize=13f
        setTypeface(typeface,Typeface.BOLD)
        setTextColor(Color.WHITE)
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(color),color)).apply{
            cornerRadius=dp(10).toFloat()
            setStroke(dp(1),darken(color))
        }
        elevation=dp(4).toFloat()
        setPadding(dp(14),0,dp(14),0)
    }

    private fun lighten(c:Int):Int{
        val r=(Color.red(c)+38).coerceAtMost(255)
        val g=(Color.green(c)+38).coerceAtMost(255)
        val b=(Color.blue(c)+38).coerceAtMost(255)
        return Color.rgb(r,g,b)
    }

    private fun darken(c:Int):Int=
        Color.rgb((Color.red(c)-28).coerceAtLeast(0),(Color.green(c)-28).coerceAtLeast(0),(Color.blue(c)-28).coerceAtLeast(0))

    private fun addTab(content:FrameLayout,tag:String,label:String){
        val lv=ListView(this).apply{
            id=View.generateViewId()
            dividerHeight=1
            setBackgroundColor(Color.WHITE)
        }
        content.addView(lv,FrameLayout.LayoutParams(-1,-1))
        lists[tag]=lv
        tabHost.addTab(tabHost.newTabSpec(tag).setIndicator(label).setContent(lv.id))
    }

    private fun styleTabs(){
        for(i in 0 until tabHost.tabWidget.tabCount){
            val child=tabHost.tabWidget.getChildAt(i)
            child.setPadding(dp(12),0,dp(12),0)
            child.setBackgroundColor(Color.rgb(236,240,245))
            val title=findTextView(child)
            title?.apply{
                setTextColor(Color.rgb(48,62,78))
                textSize=12f
                setTypeface(typeface,Typeface.BOLD)
            }
        }
    }

    private fun findTextView(v:View):TextView?{
        if(v is TextView)return v
        if(v is ViewGroup){
            for(i in 0 until v.childCount){
                val found=findTextView(v.getChildAt(i))
                if(found!=null)return found
            }
        }
        return null
    }

    private fun currentSubnet():String{
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for(network in cm.allNetworks){
            val lp=cm.getLinkProperties(network)?:continue
            for(la in lp.linkAddresses){
                val addr=la.address
                if(addr is Inet4Address&&!addr.isLoopbackAddress){
                    val p=la.prefixLength
                    val b=addr.address.map{it.toInt() and 255}
                    val v=(b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
                    val mask=-1 shl (32-p)
                    val n=v and mask
                    return ((n ushr 24) and 255).toString()+"."+((n ushr 16) and 255).toString()+"."+((n ushr 8) and 255).toString()+"."+(n and 255).toString()+"/"+p
                }
            }
        }
        return "192.168.1.0/24"
    }

    private fun scan(){
        val parts=subnet.text.toString().trim().split("/")
        if(parts.size!=2){
            Toast.makeText(this,"Enter subnet like 10.1.2.0/24",Toast.LENGTH_SHORT).show()
            return
        }
        val prefix=parts[1].toIntOrNull()?:return
        if(prefix !in 16..30){
            Toast.makeText(this,"Use /16 through /30",Toast.LENGTH_SHORT).show()
            return
        }

        val b=InetAddress.getByName(parts[0]).address.map{it.toInt() and 255}
        val value=(b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
        val mask=(0xffffffffL shl (32-prefix)) and 0xffffffffL
        val network=value and mask
        val maxHosts=minOf((1L shl (32-prefix))-2,4094)
        val out=java.util.Collections.synchronizedList(mutableListOf<Dev>())
        counts.text="Scanning "+subnet.text.toString()+" ..."

        Thread{
            val futures=(1L..maxHosts).map{i->
                pool.submit{
                    val v=network+i
                    val ip=((v shr 24) and 255).toString()+"."+((v shr 16) and 255).toString()+"."+((v shr 8) and 255).toString()+"."+(v and 255).toString()
                    val started=System.currentTimeMillis()
                    val up=try{InetAddress.getByName(ip).isReachable(700)}catch(_:Exception){false}
                    val host=if(up)try{InetAddress.getByName(ip).canonicalHostName}catch(_:Exception){""}else""
                    val mac=if(up)macForIp(ip)else""
                    val vendor=vendorFromMac(mac)
                    val type=classify(host,vendor)
                    val ms=if(up)System.currentTimeMillis()-started else -1L
                    out.add(Dev(ip,host,mac,vendor,up,ms,type))
                }
            }
            futures.forEach{it.get()}
            devs=out.sortedWith(compareBy{ipNumber(it.ip)})
            compareMode=false
            runOnUiThread{renderAll()}
        }.start()
    }

    private fun ipNumber(ip:String):Long{
        val p=ip.split(".").map{it.toLong()}
        return (p[0] shl 24) or (p[1] shl 16) or (p[2] shl 8) or p[3]
    }

    private fun macForIp(ip:String):String{
        try{
            val f=File("/proc/net/arp")
            if(!f.exists())return ""
            f.forEachLine{line->
                val parts=line.trim().split(Regex("\\s+"))
                if(parts.size>=4&&parts[0]==ip){
                    val mac=parts[3].uppercase()
                    if(mac!="00:00:00:00:00:00")return mac
                }
            }
        }catch(_:Exception){}
        return ""
    }

    private fun vendorFromMac(mac:String):String{
        val p=mac.replace(":","").replace("-","").uppercase()
        if(p.length<6)return ""
        val aruba=listOf("000B86","001A1E","00246C","186472","24DEC6","40E3D6","6CF37F","84D47E","94B40F","B45D50")
        val cisco=listOf("00000C","000142","000143","000AB7","000BFC","000C30","000D28","000D65","000E38","000E83","000F23","001007","00100B","001011","001054","00105A","00107B","0010A6","0010F6","001120","001121","00115C","001192","0011BB")
        if(aruba.any{p.startsWith(it)})return "Aruba"
        if(cisco.any{p.startsWith(it)})return "Cisco"
        return ""
    }

    private fun classify(host:String,vendor:String):String{
        val h=host.lowercase()
        val v=vendor.lowercase()
        return when{
            v.contains("aruba")||v.contains("cisco")||h.contains("aruba")||h.contains("cisco")||h.startsWith("ap-")||h.startsWith("ap")->"Access Point"
            h.contains("desktop")||h.contains("laptop")||h.contains("workstation")||h.contains("pc-")->"PC / Desktop"
            h.contains("printer")||h.contains("xerox")||h.contains("canon")||h.contains("brother")||
            h.contains("camera")||h.contains("phone")||h.contains("iphone")||h.contains("android")||
            h.contains("switch")||h.contains("router")||h.contains("gateway")||h.contains("lantronix")->"Other Device"
            else->"Unknown"
        }
    }

    private fun buildRows():MutableList<Dev>{
        val rows=devs.map{it.copy(change="")}.toMutableList()
        if(compareMode){
            val old=before.associateBy{it.ip}
            for(d in rows){
                if(d.up&&!old.containsKey(d.ip))d.change="NEW"
                else if(d.up&&old.containsKey(d.ip)){
                    val prior=old[d.ip]!!
                    if(prior.host!=d.host||prior.mac!=d.mac)d.change="CHANGED"
                }
            }
            rows.addAll(before.filter{it.up&&!devs.any{n->n.ip==it.ip&&n.up}}.map{it.copy(up=false,change="MISSING")})
        }
        return rows
    }

    private fun statusFilter(rows:List<Dev>):List<Dev>{
        return when(filter.selectedItem?.toString()?:"All"){
            "Pingable"->rows.filter{it.up}
            "No Ping"->rows.filter{!it.up}
            else->rows
        }
    }

    private fun renderAll(){
        if(!::filter.isInitialized||!::tabHost.isInitialized)return
        val all=buildRows()
        setList("all",statusFilter(all))
        setList("aps",statusFilter(all.filter{it.type=="Access Point"}))
        setList("nonaps",statusFilter(all.filter{it.up&&it.type!="Access Point"}))
        setList("pcs",statusFilter(all.filter{it.type=="PC / Desktop"}))
        setList("other",statusFilter(all.filter{it.type=="Other Device"}))
        setList("unknown",statusFilter(all.filter{it.type=="Unknown"}))
        setList("changes",statusFilter(all.filter{it.change.isNotEmpty()}))

        val ping=devs.count{it.up}
        val aps=devs.count{it.up&&it.type=="Access Point"}
        val nonaps=devs.count{it.up&&it.type!="Access Point"}
        val noPing=devs.count{!it.up}

        pingBtn.text="Pingable: "+ping
        apBtn.text="APs: "+aps
        nonApBtn.text="Non-APs: "+nonaps
        noPingBtn.text="Not Responding: "+noPing
        counts.text="Current  •  Pingable "+ping+"  •  APs "+aps+"  •  Non-APs "+nonaps+"  •  Not Responding "+noPing

        if(before.isEmpty()){
            compareCounts.text=""
        }else{
            val bp=before.count{it.up}
            val ba=before.count{it.up&&it.type=="Access Point"}
            val bn=before.count{it.up&&it.type!="Access Point"}
            val bno=before.count{!it.up}
            compareCounts.text="Before → Now  •  Pingable "+bp+"→"+ping+"  •  APs "+ba+"→"+aps+"  •  Non-APs "+bn+"→"+nonaps+"  •  Not Responding "+bno+"→"+noPing
        }
    }

    private fun setList(tag:String,rows:List<Dev>){
        lists[tag]?.adapter=object:ArrayAdapter<Dev>(this,android.R.layout.simple_list_item_1,rows){
            override fun getView(position:Int,convertView:View?,parent:ViewGroup):View{
                val view=super.getView(position,convertView,parent)
                val d=getItem(position)!!
                val text=view.findViewById<TextView>(android.R.id.text1)
                val state=if(d.up)"UP" else if(d.change=="MISSING")"MISSING" else "NO PING"
                val latency=if(d.ms>=0)d.ms.toString()+"ms" else ""
                var s=d.ip+"   "+state
                if(d.host.isNotBlank())s=s+"   "+d.host
                if(d.mac.isNotBlank())s=s+"   "+d.mac
                if(d.vendor.isNotBlank())s=s+"   "+d.vendor
                s=s+"   "+d.type
                if(latency.isNotBlank())s=s+"   "+latency
                if(d.change.isNotEmpty())s=s+"   "+d.change

                text.text=s
                text.textSize=13f
                text.setPadding(dp(12),dp(10),dp(12),dp(10))
                text.setTextColor(if(d.up)Color.rgb(23,84,39) else Color.rgb(135,32,32))
                if(d.change.isNotEmpty())text.setTypeface(text.typeface,Typeface.BOLD)
                else text.setTypeface(text.typeface,Typeface.NORMAL)

                view.background=GradientDrawable().apply{
                    setColor(if(d.up)Color.rgb(224,246,228) else Color.rgb(255,229,229))
                    setStroke(1,if(d.up)Color.rgb(186,226,194) else Color.rgb(239,194,194))
                }
                return view
            }
        }
    }
}
