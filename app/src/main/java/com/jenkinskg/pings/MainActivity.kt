package com.jenkinskg.pings

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.net.ConnectivityManager
import android.widget.*
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

data class Dev(val ip:String,val host:String,val up:Boolean,val ms:Long,val type:String)

class MainActivity:Activity(){
    private lateinit var subnet:EditText
    private lateinit var list:ListView
    private lateinit var counts:TextView
    private var devs=listOf<Dev>()
    private var before=listOf<Dev>()
    private val pool=Executors.newFixedThreadPool(32)

    override fun onCreate(b:Bundle?){
        super.onCreate(b)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        subnet=EditText(this).apply{setText("192.168.1.0/24")}
        val row=LinearLayout(this)
        fun button(t:String,f:()->Unit)=Button(this).apply{text=t;setOnClickListener{f()}}
        row.addView(button("Current Subnet"){subnet.setText(currentSubnet())})
        row.addView(button("Scan"){scan()})
        row.addView(button("Set Before"){before=devs;Toast.makeText(this,"Baseline saved",Toast.LENGTH_SHORT).show()})
        row.addView(button("Compare"){show(true)})
        counts=TextView(this)
        list=ListView(this)
        root.addView(subnet);root.addView(row);root.addView(counts)
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
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
        if(parts.size!=2){Toast.makeText(this,"Enter subnet like 10.1.2.0/24",Toast.LENGTH_SHORT).show();return}
        val prefix=parts[1].toIntOrNull()?:return
        if(prefix !in 16..30){Toast.makeText(this,"Use /16 through /30",Toast.LENGTH_SHORT).show();return}
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
            devs=out.sortedBy{it.ip}
            runOnUiThread{show(false)}
        }.start()
    }

    private fun classify(host:String):String{
        val h=host.lowercase()
        return when{
            h.contains("aruba")||h.contains("cisco")||h.startsWith("ap")->"Access Point"
            h.contains("pc")||h.contains("desktop")||h.contains("laptop")->"PC / Desktop"
            else->"Unknown"
        }
    }

    private fun show(compare:Boolean){
        val old=before.associateBy{it.ip}
        val lines=devs.filter{it.up}.map{d->
            val change=if(compare&&!old.containsKey(d.ip))" NEW" else""
            d.ip+"  "+d.host+"  "+d.type+"  "+d.ms+"ms"+change
        }.toMutableList()
        if(compare)lines.addAll(before.filter{it.up&&!devs.any{n->n.ip==it.ip&&n.up}}.map{it.ip+"  "+it.host+"  MISSING"})
        list.adapter=ArrayAdapter(this,android.R.layout.simple_list_item_1,lines)
        counts.text="Pingable "+devs.count{it.up}+" | AP "+devs.count{it.up&&it.type=="Access Point"}+" | Unknown "+devs.count{it.up&&it.type=="Unknown"}+" | No Ping "+devs.count{!it.up}
    }
}
