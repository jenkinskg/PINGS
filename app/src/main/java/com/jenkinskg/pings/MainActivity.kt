package com.jenkinskg.pings

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.view.View
import android.widget.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

data class Dev(
    val ip:String,
    val host:String,
    val up:Boolean,
    val ms:Long,
    val type:String,
    var change:String=""
)

class MainActivity:Activity(){
    private lateinit var subnet:EditText
    private lateinit var filter:Spinner
    private lateinit var counts:TextView
    private lateinit var tabHost:TabHost
    private val lists=linkedMapOf<String,ListView>()
    private var devs=listOf<Dev>()
    private var before=listOf<Dev>()
    private var compareMode=false
    private val pool=Executors.newFixedThreadPool(32)

    override fun onCreate(b:Bundle?){
        super.onCreate(b)

        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        subnet=EditText(this).apply{setText("192.168.1.0/24")}

        val row1=HorizontalScrollView(this)
        val buttons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun button(t:String,f:()->Unit)=Button(this).apply{text=t;setOnClickListener{f()}}
        buttons.addView(button("Current Subnet"){subnet.setText(currentSubnet())})
        buttons.addView(button("Scan Now"){scan()})
        buttons.addView(button("Set Before"){
            before=devs.map{it.copy()}
            compareMode=false
            renderAll()
            Toast.makeText(this,"Baseline saved",Toast.LENGTH_SHORT).show()
        })
        buttons.addView(button("Compare"){
            compareMode=true
            renderAll()
            tabHost.currentTabByTag="changes"
        })
        row1.addView(buttons)

        val filterRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        filterRow.addView(TextView(this).apply{text="Show: ";setPadding(12,16,8,0)})
        filter=Spinner(this)
        val filterItems=listOf("All","Pingable","No Ping")
        filter.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,filterItems)
        filter.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){renderAll()}
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
        }
        filterRow.addView(filter)

        counts=TextView(this).apply{setPadding(12,10,12,10)}

        tabHost=TabHost(this)
        val tabLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val tabWidget=TabWidget(this).apply{id=android.R.id.tabs}
        val content=FrameLayout(this).apply{id=android.R.id.tabcontent}
        tabLayout.addView(tabWidget,LinearLayout.LayoutParams(-1,-2))
        tabLayout.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        tabHost.addView(tabLayout)
        tabHost.setup()

        addTab(content,"all","All")
        addTab(content,"aps","APs")
        addTab(content,"pcs","PCs")
        addTab(content,"other","Other")
        addTab(content,"unknown","Unknown")
        addTab(content,"changes","Missing/Changed")

        root.addView(subnet)
        root.addView(row1)
        root.addView(filterRow)
        root.addView(counts)
        root.addView(tabHost,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    private fun addTab(content:FrameLayout,tag:String,label:String){
        val lv=ListView(this)
        lv.id=View.generateViewId()
        content.addView(lv,FrameLayout.LayoutParams(-1,-1))
        lists[tag]=lv
        tabHost.addTab(tabHost.newTabSpec(tag).setIndicator(label).setContent(lv.id))
    }

    private fun currentSubnet():String{
        val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for(network in cm.allNetworks){
            val lp=cm.getLinkProperties(network)?:continue
            for(la in lp.linkAddresses){
                val addr=la.address
                if(addr is Inet4Address && !addr.isLoopbackAddress){
                    val p=la.prefixLength
                    val b=addr.address.map{it.toInt() and 255}
                    val v=(b[0] shl 24) or (b[1] shl 16) or (b[2] shl 8) or b[3]
                    val mask=-1 shl (32-p)
                    val n=v and mask
                    return (((n ushr 24) and 255).toString()+"."+((n ushr 16) and 255).toString()+"."+((n ushr 8) and 255).toString()+"."+(n and 255).toString()+"/"+p)
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
        counts.text="Scanning..."

        Thread{
            val futures=(1L..maxHosts).map{i->
                pool.submit{
                    val v=network+i
                    val ip=((v shr 24) and 255).toString()+"."+((v shr 16) and 255).toString()+"."+((v shr 8) and 255).toString()+"."+(v and 255).toString()
                    val started=System.currentTimeMillis()
                    val up=try{InetAddress.getByName(ip).isReachable(700)}catch(_:Exception){false}
                    val host=if(up)try{InetAddress.getByName(ip).canonicalHostName}catch(_:Exception){""}else""
                    val type=classify(host)
                    val ms=if(up)System.currentTimeMillis()-started else -1L
                    out.add(Dev(ip,host,up,ms,type))
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

    private fun classify(host:String):String{
        val h=host.lowercase()
        return when{
            h.contains("aruba")||h.contains("cisco")||h.startsWith("ap-")||h.startsWith("ap")->"Access Point"
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
                else if(d.up&&old.containsKey(d.ip)&&old[d.ip]?.host!=d.host)d.change="CHANGED"
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
        setList("pcs",statusFilter(all.filter{it.type=="PC / Desktop"}))
        setList("other",statusFilter(all.filter{it.type=="Other Device"}))
        setList("unknown",statusFilter(all.filter{it.type=="Unknown"}))
        setList("changes",statusFilter(all.filter{it.change.isNotEmpty()}))

        val ping=devs.count{it.up}
        val aps=devs.count{it.up&&it.type=="Access Point"}
        val pcs=devs.count{it.up&&it.type=="PC / Desktop"}
        val other=devs.count{it.up&&it.type=="Other Device"}
        val unknown=devs.count{it.up&&it.type=="Unknown"}
        val noPing=devs.count{!it.up}
        counts.text="Pingable "+ping+" | AP "+aps+" | PC "+pcs+" | Other "+other+" | Unknown "+unknown+" | No Ping "+noPing
    }

    private fun setList(tag:String,rows:List<Dev>){
        lists[tag]?.adapter=object:ArrayAdapter<Dev>(this,android.R.layout.simple_list_item_1,rows){
            override fun getView(position:Int,convertView:android.view.View?,parent:android.view.ViewGroup):android.view.View{
                val view=super.getView(position,convertView,parent)
                val d=getItem(position)!!
                val text=view.findViewById<TextView>(android.R.id.text1)
                val state=if(d.up)"UP" else if(d.change=="MISSING")"MISSING" else "NO PING"
                val latency=if(d.ms>=0)d.ms.toString()+"ms" else ""
                var s=d.ip+"  "+state+"  "+d.host+"  "+d.type+"  "+latency
                if(d.change.isNotEmpty())s=s+"  "+d.change
                text.text=s
                text.setTextColor(if(d.up)0xff0b3d0b.toInt() else 0xff8b0000.toInt())
                view.setBackgroundColor(if(d.up)0xffc8f7c5.toInt() else 0xffffd6d6.toInt())
                return view
            }
        }
    }
}
