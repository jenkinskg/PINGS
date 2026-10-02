package com.jenkinskg.pings

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.content.Intent
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
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import java.io.ByteArrayOutputStream

data class Dev(
    val ip:String,
    val host:String,
    val mac:String,
    val vendor:String,
    val up:Boolean,
    val ms:Long,
    val type:String,
    val group:String="",
    var change:String=""
)

class MainActivity:Activity(){
    private lateinit var subnet:EditText
    private lateinit var filter:Spinner
    private lateinit var repeat:Spinner
    private lateinit var counts:TextView
    private lateinit var compareCounts:TextView
    private lateinit var list:ListView
    private lateinit var pingBtn:Button
    private lateinit var apBtn:Button
    private lateinit var nonApBtn:Button
    private lateinit var noPingBtn:Button

    private val tabButtons=linkedMapOf<String,Button>()
    private var currentTab="all"
    private var devs=listOf<Dev>()
    private var before=listOf<Dev>()
    private var compareMode=false
    private val selectedIps=linkedSetOf<String>()
    private val pool=Executors.newFixedThreadPool(32)
    private val handler=Handler(Looper.getMainLooper())
    private var repeatMs=0L
    private val exportRequestCode=711
    private var pendingExportContent:String?=null
    private val prefs by lazy { getSharedPreferences("device_classifications", Context.MODE_PRIVATE) }
    private val learnedPrefs by lazy { getSharedPreferences("learned_mac_prefixes", Context.MODE_PRIVATE) }

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
            text="Network Availability Monitor • v0.14"
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
            filter.setSelection(0)
            currentTab="changes"
            renderAll()
        },buttonLp())
        buttons.addView(actionButton("Export CSV"){
            startExport("csv")
        },buttonLp())
        buttons.addView(actionButton("Export TXT"){
            startExport("txt")
        },buttonLp())
        buttons.addView(actionButton("Switch VLANs"){
            showSwitchVlansDialog()
        },buttonLp())
        buttons.addView(actionButton("Classify Selected"){
            val selected=devs.filter{selectedIps.contains(it.ip)}
            if(selected.isEmpty()) Toast.makeText(this,"Long-press devices to select them",Toast.LENGTH_SHORT).show()
            else showClassificationDialog(selected)
        },buttonLp())
        buttons.addView(actionButton("Clear Selection"){
            selectedIps.clear()
            renderAll()
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
        pingBtn.setOnClickListener{filter.setSelection(1);currentTab="all";renderAll()}
        apBtn.setOnClickListener{filter.setSelection(1);currentTab="aps";renderAll()}
        nonApBtn.setOnClickListener{filter.setSelection(1);currentTab="nonaps";renderAll()}
        noPingBtn.setOnClickListener{filter.setSelection(2);currentTab="all";renderAll()}
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

        val tabScroll=HorizontalScrollView(this).apply{
            isHorizontalScrollBarEnabled=false
            setBackgroundColor(Color.rgb(236,240,245))
        }
        val tabRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setPadding(dp(8),dp(5),dp(8),dp(5))
        }
        addTabButton(tabRow,"all","All Pings")
        addTabButton(tabRow,"aps","APs")
        addTabButton(tabRow,"nonaps","Non-APs")
        addTabButton(tabRow,"pcs","PCs")
        addTabButton(tabRow,"other","Other")
        addTabButton(tabRow,"unknown","Unknown")
        addTabButton(tabRow,"groups","Custom Groups")
        addTabButton(tabRow,"changes","Missing/Changed")
        tabScroll.addView(tabRow)

        list=ListView(this).apply{
            setBackgroundColor(Color.WHITE)
            dividerHeight=1
            setOnItemLongClickListener{_,_,position,_->
                val rows=visibleRows()
                if(position in rows.indices){
                    val ip=rows[position].ip
                    if(selectedIps.contains(ip))selectedIps.remove(ip) else selectedIps.add(ip)
                    renderAll()
                    true
                }else false
            }
            setOnItemClickListener{_,_,position,_->
                if(selectedIps.isNotEmpty()){
                    val rows=visibleRows()
                    if(position in rows.indices){
                        val ip=rows[position].ip
                        if(selectedIps.contains(ip))selectedIps.remove(ip) else selectedIps.add(ip)
                        renderAll()
                    }
                }
            }
        }

        root.addView(header)
        root.addView(countScroll,LinearLayout.LayoutParams(-1,dp(58)))
        root.addView(counts)
        root.addView(compareCounts)
        root.addView(tabScroll,LinearLayout.LayoutParams(-1,dp(54)))
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=exportRequestCode||resultCode!=RESULT_OK)return
        val uri=data?.data?:return
        val content=pendingExportContent?:return
        try{
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use{it.write(content)}
            Toast.makeText(this,"PINGS export saved",Toast.LENGTH_SHORT).show()
        }catch(e:Exception){
            Toast.makeText(this,"Export failed: "+(e.message?:"unknown error"),Toast.LENGTH_LONG).show()
        }finally{
            pendingExportContent=null
        }
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

    private fun addTabButton(row:LinearLayout,key:String,label:String){
        val b=Button(this).apply{
            text=label
            isAllCaps=false
            textSize=12f
            minHeight=dp(38)
            setPadding(dp(12),0,dp(12),0)
            setOnClickListener{
                currentTab=key
                renderAll()
            }
        }
        tabButtons[key]=b
        row.addView(b,LinearLayout.LayoutParams(-2,dp(42)).apply{
            setMargins(dp(3),0,dp(3),0)
        })
    }

    private fun styleTabButtons(){
        for((key,b) in tabButtons){
            val selected=key==currentTab
            b.setTypeface(b.typeface,Typeface.BOLD)
            b.setTextColor(if(selected)Color.WHITE else Color.rgb(48,62,78))
            b.background=GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if(selected)intArrayOf(Color.rgb(67,125,190),Color.rgb(42,91,150))
                else intArrayOf(Color.WHITE,Color.rgb(226,232,239))
            ).apply{
                cornerRadius=dp(8).toFloat()
                setStroke(dp(1),if(selected)Color.rgb(36,78,128) else Color.rgb(181,191,202))
            }
            b.elevation=dp(if(selected)4 else 2).toFloat()
        }
    }

    private fun showSwitchVlansDialog(){
        val layout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(8),dp(18),0)
        }

        val host=EditText(this).apply{
            hint="Switch IP or hostname"
            setText(defaultGateway())
            singleLine=true
        }
        val port=EditText(this).apply{
            hint="SSH port"
            setText("22")
            inputType=android.text.InputType.TYPE_CLASS_NUMBER
            singleLine=true
        }
        val user=EditText(this).apply{
            hint="Username"
            singleLine=true
        }
        val password=EditText(this).apply{
            hint="Password"
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            singleLine=true
        }
        val vendor=Spinner(this).apply{
            adapter=ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Auto","Cisco IOS / IOS-XE","Aruba CX","ArubaOS-Switch / ProCurve","Nortel / Avaya ERS")
            )
        }

        layout.addView(host)
        layout.addView(port)
        layout.addView(user)
        layout.addView(password)
        layout.addView(TextView(this).apply{
            text="Switch type"
            setPadding(0,dp(8),0,0)
        })
        layout.addView(vendor)

        val scroll=ScrollView(this).apply{addView(layout)}

        android.app.AlertDialog.Builder(this)
            .setTitle("Switch VLANs")
            .setMessage("Enter the management IP/hostname for the switch. Credentials are used for this connection only and are not saved.")
            .setView(scroll)
            .setPositiveButton("Connect"){_,_->
                val p=port.text.toString().toIntOrNull()?:22
                loadSwitchVlans(host.text.toString().trim(),p,user.text.toString(),password.text.toString(),vendor.selectedItem.toString())
            }
            .setNegativeButton("Cancel",null)
            .show()
    }

    private fun defaultGateway():String{
        return try{
            val cm=getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            for(network in cm.allNetworks){
                val lp=cm.getLinkProperties(network)?:continue
                for(route in lp.routes){
                    val gw=route.gateway
                    if(route.isDefaultRoute&&gw is Inet4Address)return gw.hostAddress?:""
                }
            }
            ""
        }catch(_:Exception){""}
    }

    private fun loadSwitchVlans(host:String,port:Int,user:String,password:String,requestedVendor:String){
        if(host.isBlank()||user.isBlank()){
            Toast.makeText(this,"Enter switch IP/hostname and username",Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this,"Connecting to "+host+"...",Toast.LENGTH_SHORT).show()

        Thread{
            try{
                val jsch=JSch()
                val session=jsch.getSession(user,host,port)
                session.setPassword(password)
                session.setConfig("StrictHostKeyChecking","no")
                session.timeout=12000
                session.connect(12000)

                val version=runSshCommand(session,"show version")
                val detected=detectSwitchVendor(requestedVendor,version)
                var output=""
                var usedCommand=""

                for(cmd in vlanCommands(detected)){
                    val candidate=runSshCommand(session,cmd)
                    if(looksLikeUsefulVlanOutput(candidate)){
                        output=candidate
                        usedCommand=cmd
                        break
                    }
                }
                session.disconnect()

                if(output.isBlank())throw IllegalStateException(
                    "Connected successfully, but no supported VLAN command returned usable output."
                )

                val text=buildString{
                    appendLine("PINGS Switch VLAN Discovery")
                    appendLine("Switch: "+host)
                    appendLine("Detected/selected platform: "+detected)
                    appendLine("Command: "+usedCommand)
                    appendLine("-".repeat(60))
                    append(output.trimEnd())
                }

                runOnUiThread{showSwitchOutput(text)}
            }catch(e:Exception){
                runOnUiThread{
                    showSwitchOutput(
                        "ERROR: "+(e.message?:"Connection failed")+
                        "\n\nThe account must be allowed to SSH to the switch and run read-only show commands."
                    )
                }
            }
        }.start()
    }

    private fun runSshCommand(session:com.jcraft.jsch.Session,command:String):String{
        val channel=session.openChannel("exec") as ChannelExec
        val err=ByteArrayOutputStream()
        channel.setCommand(command)
        channel.setErrStream(err)
        val input=channel.inputStream
        channel.connect(15000)
        val out=input.bufferedReader().use{it.readText()}
        while(!channel.isClosed)Thread.sleep(25)
        channel.disconnect()
        val errors=err.toString(Charsets.UTF_8.name())
        return if(errors.isBlank())out else out+"\n"+errors
    }

    private fun detectSwitchVendor(requested:String,version:String):String{
        if(requested!="Auto")return requested
        val v=version.lowercase()
        return when{
            v.contains("arubaos-cx")||v.contains("aos-cx")->"Aruba CX"
            v.contains("procurve")||v.contains("arubaos-switch")||
                v.contains("hewlett packard enterprise")||v.contains("hp j")->"ArubaOS-Switch / ProCurve"
            v.contains("nortel")||v.contains("avaya")||v.contains("ethernet routing switch")->"Nortel / Avaya ERS"
            v.contains("cisco")||v.contains("ios xe")||v.contains("ios-xe")->"Cisco IOS / IOS-XE"
            else->"Auto"
        }
    }

    private fun vlanCommands(vendor:String):List<String>{
        return when(vendor){
            "Cisco IOS / IOS-XE"->listOf("show vlan brief","show vlan")
            "Aruba CX"->listOf("show vlan")
            "ArubaOS-Switch / ProCurve"->listOf("show vlans","show vlan")
            "Nortel / Avaya ERS"->listOf("show vlan","show vlan basic")
            else->listOf("show vlan brief","show vlan","show vlans","show vlan basic")
        }
    }

    private fun looksLikeUsefulVlanOutput(text:String):Boolean{
        if(text.isBlank())return false
        val t=text.lowercase()
        if(t.contains("invalid input")||t.contains("unknown command")||
            t.contains("unrecognized command")||t.contains("incomplete command")||
            t.contains("ambiguous command")||t.contains("command not found"))return false

        return t.contains("vlan")&&
            (t.contains("name")||t.contains("status")||t.contains("port")||t.contains("vid"))
    }

    private fun showSwitchOutput(text:String){
        val tv=TextView(this).apply{
            this.text=text
            typeface=Typeface.MONOSPACE
            textSize=12f
            setTextIsSelectable(true)
            setPadding(dp(14),dp(12),dp(14),dp(12))
        }
        val scroll=ScrollView(this).apply{addView(tv)}
        android.app.AlertDialog.Builder(this)
            .setTitle("Switch VLANs")
            .setView(scroll)
            .setPositiveButton("Close",null)
            .setNeutralButton("Copy"){_,_->android.content.ClipboardManager::class.java.let{
                val cb=getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("PINGS VLANs",text))
                Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show()
            }}
            .show()
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
                    var vendor=vendorFromMac(mac)
                    var type=classify(host,vendor)
                    var group=""

                    val learned=getLearnedPrefix(mac)
                    if(learned!=null){
                        vendor=learned.first
                        type=learned.second
                    }

                    val manual=getOverride(ip,host,mac)
                    if(manual!=null){
                        vendor=manual.first
                        type=manual.second
                        group=manual.third
                    }
                    val ms=if(up)System.currentTimeMillis()-started else -1L
                    out.add(Dev(ip,host,mac,vendor,up,ms,type,group))
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
            for(line in f.readLines()){
                val parts=line.trim().split(Regex("\\s+"))
                if(parts.size>=4&&parts[0]==ip){
                    val mac=parts[3].uppercase()
                    if(mac!="00:00:00:00:00:00")return mac
                }
            }
        }catch(_:Exception){}
        return ""
    }

    private fun candidateKeys(ip:String,host:String,mac:String):List<String>{
        val out=mutableListOf<String>()
        if(mac.isNotBlank())out.add("mac:"+mac.replace(":","").replace("-","").uppercase())
        if(host.isNotBlank())out.add("host:"+host.trim().lowercase())
        if(ip.isNotBlank())out.add("ip:"+ip.trim())
        return out
    }

    data class Override(val first:String,val second:String,val third:String)

    private fun getOverride(ip:String,host:String,mac:String):Override?{
        for(key in candidateKeys(ip,host,mac)){
            val raw=prefs.getString(key,null)?:continue
            val parts=raw.split("|")
            if(parts.size>=2)return Override(parts[0],parts[1],if(parts.size>=3)parts[2]else"")
        }
        return null
    }

    private fun saveOverride(d:Dev,vendor:String,type:String,group:String=d.group){
        val key=candidateKeys(d.ip,d.host,d.mac).firstOrNull()?:return
        prefs.edit().putString(key,vendor+"|"+type+"|"+group).apply()
        devs=devs.map{
            if(it.ip==d.ip)it.copy(vendor=vendor,type=type,group=group) else it
        }
    }

    private fun clearOverride(d:Dev){
        val edit=prefs.edit()
        for(key in candidateKeys(d.ip,d.host,d.mac))edit.remove(key)
        edit.apply()
        devs=devs.map{
            if(it.ip==d.ip){
                var autoVendor=vendorFromMac(it.mac)
                var autoType=classify(it.host,autoVendor)
                val learned=getLearnedPrefix(it.mac)
                if(learned!=null){
                    autoVendor=learned.first
                    autoType=learned.second
                }
                it.copy(vendor=autoVendor,type=autoType,group="")
            }else it
        }
        renderAll()
    }

    private fun showClassificationDialog(items:List<Dev>){
        val choices=arrayOf(
            "Mark as HP Aruba Access Point (learn MAC prefix)",
            "Mark as Cisco / Meraki Access Point",
            "Mark as PC / Desktop",
            "Mark as Non-AP / Other Device",
            "Mark as Unknown",
            "Assign to Custom Group...",
            "Clear Custom Group",
            "Forget Learned MAC Prefix",
            "Clear Manual Classification"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Classify "+items.size+" selected device"+(if(items.size==1)"" else "s"))
            .setItems(choices){_,which->
                when(which){
                    0->learnAndClassifyAruba(items)
                    1->items.forEach{saveOverride(it,"Cisco / Meraki","Access Point")}
                    2->items.forEach{saveOverride(it,"Manual","PC / Desktop")}
                    3->items.forEach{saveOverride(it,"Manual","Other Device")}
                    4->items.forEach{saveOverride(it,"","Unknown")}
                    5->promptCustomGroup(items)
                    6->items.forEach{saveOverride(it,it.vendor,it.type,"")}
                    7->forgetLearnedPrefixes(items)
                    8->items.forEach{clearOverride(it)}
                }
                if(which!=5){
                    selectedIps.clear()
                    renderAll()
                }
            }
            .show()
    }

    private fun macPrefix(mac:String):String?{
        val normalized=mac.replace(":","").replace("-","").trim().uppercase()
        return if(normalized.length>=6)normalized.substring(0,6) else null
    }

    private fun formatMacPrefix(prefix:String):String{
        return if(prefix.length>=6)
            prefix.substring(0,2)+":"+prefix.substring(2,4)+":"+prefix.substring(4,6)
        else prefix
    }

    private fun getLearnedPrefix(mac:String):Override?{
        val prefix=macPrefix(mac)?:return null
        val raw=learnedPrefs.getString(prefix,null)?:return null
        val parts=raw.split("|")
        return if(parts.size>=2)Override(parts[0],parts[1],"") else null
    }

    private fun learnAndClassifyAruba(items:List<Dev>){
        val learned=linkedSetOf<String>()

        for(d in items){
            saveOverride(d,"HP Aruba","Access Point")
            val prefix=macPrefix(d.mac)
            if(prefix!=null){
                learnedPrefs.edit().putString(prefix,"HP Aruba|Access Point").apply()
                learned.add(prefix)
            }
        }

        if(learned.isNotEmpty()){
            devs=devs.map{d->
                val manual=getOverride(d.ip,d.host,d.mac)
                val learnedRule=getLearnedPrefix(d.mac)
                if(manual==null&&learnedRule!=null)
                    d.copy(vendor=learnedRule.first,type=learnedRule.second)
                else d
            }

            Toast.makeText(
                this,
                "Learned Aruba prefix"+(if(learned.size==1)" " else "es ")+
                    learned.joinToString(", "){formatMacPrefix(it)},
                Toast.LENGTH_LONG
            ).show()
        }else{
            Toast.makeText(
                this,
                "No MAC address available; PINGS remembered only the selected device.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun forgetLearnedPrefixes(items:List<Dev>){
        val edit=learnedPrefs.edit()
        var removed=0

        for(d in items){
            val prefix=macPrefix(d.mac)?:continue
            if(learnedPrefs.contains(prefix)){
                edit.remove(prefix)
                removed++
            }
        }
        edit.apply()

        if(removed==0){
            Toast.makeText(this,"No learned MAC prefix found",Toast.LENGTH_SHORT).show()
            return
        }

        devs=devs.map{d->
            val manual=getOverride(d.ip,d.host,d.mac)
            if(manual!=null){
                d.copy(vendor=manual.first,type=manual.second,group=manual.third)
            }else{
                var vendor=vendorFromMac(d.mac)
                var type=classify(d.host,vendor)
                val learned=getLearnedPrefix(d.mac)
                if(learned!=null){
                    vendor=learned.first
                    type=learned.second
                }
                d.copy(vendor=vendor,type=type)
            }
        }
        Toast.makeText(this,"Forgot "+removed+" learned MAC prefix"+(if(removed==1)"" else "es"),Toast.LENGTH_SHORT).show()
    }

    private fun promptCustomGroup(items:List<Dev>){
        val input=EditText(this).apply{hint="Group name"}
        android.app.AlertDialog.Builder(this)
            .setTitle("Custom Group")
            .setView(input)
            .setPositiveButton("Save"){_,_->
                val group=input.text.toString().trim()
                if(group.isNotBlank())items.forEach{saveOverride(it,it.vendor,it.type,group)}
                selectedIps.clear()
                renderAll()
            }
            .setNegativeButton("Cancel",null)
            .show()
    }

    private fun vendorFromMac(mac:String):String{
        val p=mac.replace(":","").replace("-","").uppercase()
        if(p.length<6)return ""

        val aruba=listOf(
            "000B86","001A1E","00246C","204C03","24DEC6","40E3D6","482F6B",
            "6026EF","703A0E","84D47E","94B40F","988F00","ACA31E","B01F8C",
            "B45D50","D8C7C8","E81098","F05C19","F42E7F"
        )
        val hpeAruba=listOf("C8B5AD")
        val cisco=listOf(
            "00077D","00141B","001AA1","00270D","2C3124","380E4D","40A6E8",
            "70695A","A0ECF9","F44E05","A44C11"
        )
        val meraki=listOf(
            "00180A","08F1B3","0C7BC8","149F43","E0553D","AC17C8"
        )

        if(aruba.any{p.startsWith(it)})return "HP Aruba"
        if(hpeAruba.any{p.startsWith(it)})return "HPE / Aruba"
        if(meraki.any{p.startsWith(it)})return "Cisco Meraki"
        if(cisco.any{p.startsWith(it)})return "Cisco Systems"
        if(p.startsWith("001BED"))return "Brocade"
        if(p.startsWith("643E8C")||p.startsWith("00259E"))return "Huawei"
        if(p.startsWith("64D1A3"))return "Sitecom"
        return ""
    }

    private fun classify(host:String,vendor:String):String{
        val h=host.lowercase()
        val v=vendor.lowercase()
        return when{
            v.contains("aruba")||
            h.contains("aironet")||h.contains("meraki")||h.contains("wireless-ap")||
            h.contains("-ap")||h.startsWith("ap-")||h.startsWith("ap")||h.startsWith("wap")->"Access Point"
            h.contains("desktop")||h.contains("laptop")||h.contains("workstation")||
            h.startsWith("pc-")||h.startsWith("win-")||h.startsWith("ws-")||
            h.startsWith("lt-")||h.startsWith("nb-")||h.startsWith("dt-")||h.contains("windows")->"PC / Desktop"
            h.contains("printer")||h.contains("xerox")||h.contains("canon")||h.contains("brother")||
            h.contains("camera")||h.contains("phone")||h.contains("iphone")||h.contains("android")||
            h.contains("switch")||h.contains("router")||h.contains("gateway")||h.contains("lantronix")->"Other Device"
            else->"Unknown"
        }
    }

    private fun buildDifferenceRows():List<Dev>{
        val diffs=mutableListOf<Dev>()
        val old=before.associateBy{it.ip}

        for(d in devs){
            if(!d.up)continue
            val prior=old[d.ip]
            if(prior==null||!prior.up){
                diffs.add(d.copy(change="NEW"))
            }else if(
                prior.host!=d.host||prior.mac!=d.mac||prior.type!=d.type||prior.group!=d.group
            ){
                diffs.add(d.copy(change="CHANGED"))
            }
        }

        for(prior in before.filter{it.up}){
            val now=devs.firstOrNull{it.ip==prior.ip}
            if(now==null||!now.up)diffs.add(prior.copy(up=false,change="MISSING"))
        }

        return diffs.sortedBy{ipNumber(it.ip)}
    }

    private fun startExport(format:String){
        if(before.isEmpty()){
            Toast.makeText(this,"Set a Before baseline first",Toast.LENGTH_LONG).show()
            return
        }
        if(devs.isEmpty()){
            Toast.makeText(this,"Run an After scan first",Toast.LENGTH_LONG).show()
            return
        }

        val csv=format=="csv"
        pendingExportContent=if(csv)buildCsvExport()else buildTextExport()
        val stamp=java.text.SimpleDateFormat("yyyyMMdd_HHmmss",java.util.Locale.US).format(java.util.Date())
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type=if(csv)"text/csv" else "text/plain"
            putExtra(Intent.EXTRA_TITLE,"PINGS_compare_"+stamp+"."+(if(csv)"csv" else "txt"))
        }
        startActivityForResult(intent,exportRequestCode)
    }

    private fun buildCsvExport():String{
        val sb=StringBuilder()
        sb.appendLine("Section,IP,Hostname,MAC,Vendor,Type,Group,Status,LatencyMs,Change")

        fun addRows(section:String,rows:List<Dev>){
            for(d in rows){
                val values=listOf(
                    section,d.ip,d.host,d.mac,d.vendor,d.type,d.group,
                    if(d.up)"Pingable" else if(d.change=="MISSING")"Missing" else "No Ping",
                    if(d.ms>=0)d.ms.toString() else "",
                    d.change
                )
                sb.appendLine(values.joinToString(","){csv(it)})
            }
        }

        addRows("BEFORE",before)
        addRows("AFTER",devs)
        addRows("DIFFERENCE",buildDifferenceRows())
        return sb.toString()
    }

    private fun buildTextExport():String{
        val sb=StringBuilder()
        sb.appendLine("PINGS Before / After / Difference Export")
        sb.appendLine("Generated: "+java.util.Date().toString())
        sb.appendLine("Subnet: "+subnet.text.toString())
        sb.appendLine()

        fun countLine(label:String,rows:List<Dev>){
            val ping=rows.count{it.up}
            val aps=rows.count{it.up&&it.type=="Access Point"}
            val nonaps=rows.count{it.up&&it.type!="Access Point"}
            val noPing=rows.count{!it.up}
            sb.appendLine(label+": Pingable "+ping+", APs "+aps+", Non-APs "+nonaps+", Not Responding "+noPing)
        }

        countLine("BEFORE COUNTS",before)
        countLine("AFTER COUNTS",devs)
        sb.appendLine()

        fun addRows(title:String,rows:List<Dev>){
            sb.appendLine("===== "+title+" =====")
            sb.appendLine("IP\tHostname\tMAC\tVendor\tType\tGroup\tStatus\tLatencyMs\tChange")
            for(d in rows){
                sb.appendLine(listOf(
                    d.ip,d.host,d.mac,d.vendor,d.type,d.group,
                    if(d.up)"Pingable" else if(d.change=="MISSING")"Missing" else "No Ping",
                    if(d.ms>=0)d.ms.toString() else "",
                    d.change
                ).joinToString("\t"))
            }
            sb.appendLine()
        }

        addRows("BEFORE",before)
        addRows("AFTER",devs)
        addRows("DIFFERENCE",buildDifferenceRows())
        return sb.toString()
    }

    private fun csv(value:String):String="\""+value.replace("\"","\"\"")+"\""

    private fun buildRows():MutableList<Dev>{
        val rows=devs.map{it.copy(change="")}.toMutableList()
        if(compareMode){
            val old=before.associateBy{it.ip}
            for(d in rows){
                val prior=old[d.ip]
                if(d.up&&(prior==null||!prior.up))d.change="NEW"
                else if(d.up&&prior!=null){
                    if(prior.host!=d.host||prior.mac!=d.mac||prior.type!=d.type||prior.group!=d.group)d.change="CHANGED"
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

    private fun visibleRows():List<Dev>{
        val all=buildRows()
        val rows=when(currentTab){
            "aps"->all.filter{it.type=="Access Point"}
            "nonaps"->all.filter{it.up&&it.type!="Access Point"}
            "pcs"->all.filter{it.type=="PC / Desktop"}
            "other"->all.filter{it.type=="Other Device"}
            "unknown"->all.filter{it.type=="Unknown"}
            "groups"->all.filter{it.group.isNotBlank()}
            "changes"->all.filter{it.change.isNotEmpty()}
            else->all
        }
        return statusFilter(rows)
    }

    private fun renderAll(){
        if(!::filter.isInitialized||!::list.isInitialized)return
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
            compareCounts.text="Before → Now  •  Pingable "+bp+"→"+ping+" ("+delta(ping-bp)+")  •  APs "+ba+"→"+aps+" ("+delta(aps-ba)+")  •  Non-APs "+bn+"→"+nonaps+" ("+delta(nonaps-bn)+")  •  Not Responding "+bno+"→"+noPing+" ("+delta(noPing-bno)+")"
        }
        styleTabButtons()
        setList(visibleRows())
    }

    private fun delta(v:Int):String=if(v>0)"+"+v else v.toString()

    private fun setList(rows:List<Dev>){
        list.adapter=object:ArrayAdapter<Dev>(this,android.R.layout.simple_list_item_1,rows){
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
                if(d.group.isNotBlank())s=s+"   ["+d.group+"]"
                if(latency.isNotBlank())s=s+"   "+latency
                if(d.change.isNotEmpty())s=s+"   "+d.change

                text.text=s
                text.textSize=13f
                text.setPadding(dp(12),dp(10),dp(12),dp(10))
                text.setTextColor(if(d.up)Color.rgb(23,84,39) else Color.rgb(135,32,32))
                if(d.change.isNotEmpty())text.setTypeface(text.typeface,Typeface.BOLD)
                else text.setTypeface(text.typeface,Typeface.NORMAL)

                view.background=GradientDrawable().apply{
                    val selected=selectedIps.contains(d.ip)
                    setColor(
                        if(selected)Color.rgb(205,225,246)
                        else if(d.up)Color.rgb(224,246,228)
                        else Color.rgb(255,229,229)
                    )
                    setStroke(1,
                        if(selected)Color.rgb(73,125,181)
                        else if(d.up)Color.rgb(186,226,194)
                        else Color.rgb(239,194,194)
                    )
                }
                return view
            }
        }
    }
}
