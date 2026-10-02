package com.kabro.autoeditor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.graphics.Color;
import android.util.Base64;
import java.io.*;
import java.nio.file.Files;
import org.json.*;

public class MainActivity extends Activity {
    private WebView web;
    private NativeRenderEngine engine;
    private volatile String lastJob = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(17,21,28));
        engine = new NativeRenderEngine(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(17,21,28));
        WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setAllowFileAccess(true); s.setAllowContentAccess(true); s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "AndroidAutoEditor");
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView v, String url) { super.onPageFinished(v,url); injectBridge(); }
        });
        setContentView(web);
        web.loadUrl("file:///android_asset/web/index.html");
    }

    private void injectBridge() {
        String js = "(function(){if(window.__AUTOEDITOR_NATIVE)return;window.__AUTOEDITOR_NATIVE=true;"+
                "const origFetch=window.fetch;"+
                "window.fetch=async function(input,init){let u=String(input&&input.url||input);if(u.endsWith('/active'))return new Response(JSON.stringify({jobId:null}),{status:200,headers:{'Content-Type':'application/json'}});if(u.endsWith('/render')){let fd=init&&init.body;if(fd&&fd.entries){let spec='';for(let [k,v] of fd.entries()){if(k==='spec')spec=v;}let audioName='';for(let [k,v] of fd.entries()){if(k==='audio'&&v&&v.name){audioName=v.name;let ab=await v.arrayBuffer();let u8=new Uint8Array(ab),x=\"\";for(let z=0;z<u8.length;z+=8192)x+=String.fromCharCode.apply(null,u8.subarray(z,Math.min(z+8192,u8.length)));AndroidAutoEditor.putFile(v.name,btoa(x));}else if(v&&v.name){let ab=await v.arrayBuffer();let u8=new Uint8Array(ab),x=\"\";for(let z=0;z<u8.length;z+=8192)x+=String.fromCharCode.apply(null,u8.subarray(z,Math.min(z+8192,u8.length)));AndroidAutoEditor.putFile(v.name,btoa(x));}}try{let so=JSON.parse(spec);if(audioName)so.audioName=audioName;spec=JSON.stringify(so);}catch(e){}let id=AndroidAutoEditor.startRender(spec);return new Response(JSON.stringify({jobId:id}),{status:200,headers:{'Content-Type':'application/json'}});}return new Response(JSON.stringify({error:'Invalid render request'}),{status:400});}if(u.endsWith('/status'))return new Response(AndroidAutoEditor.status(),{status:200,headers:{'Content-Type':'application/json'}});if(u.includes('/file')){let b64=AndroidAutoEditor.outputBase64();let bin=atob(b64),arr=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)arr[i]=bin.charCodeAt(i);return new Response(new Blob([arr],{type:'video/mp4'}),{status:200});}return origFetch.apply(this,arguments);};"+
                "window.EventSource=function(url){this.onmessage=null;this.onerror=null;this.close=()=>{this.dead=true};this.dead=false;let tick=async()=>{if(this.dead)return;try{let st=JSON.parse(AndroidAutoEditor.status());let a=st[0];if(a){if(this.onmessage)this.onmessage({data:JSON.stringify({progress:(a.percent||0)/100,done:a.status==='done',error:a.error||null})});if(a.status==='done'||a.status==='error')return;} }catch(e){}setTimeout(tick,500)};tick();};"+
                "})();";
        web.evaluateJavascript(js,null);
    }

    public class Bridge {
        @JavascriptInterface public String putFile(String name,String b64){try{return engine.putFile(name,b64)==null?"0":"1";}catch(Exception e){return "ERR:"+e.getMessage();}}
        @JavascriptInterface public String startRender(String spec){try{lastJob=engine.start(spec,new NativeRenderEngine.Listener(){public void progress(float p){} public void done(File f){} public void error(String m){}});return lastJob;}catch(Exception e){return "ERR:"+e.getMessage();}}
        @JavascriptInterface public String status(){try{JSONObject o=new JSONObject();o.put("id",lastJob);float p=engine.getProgress();o.put("percent",Math.round(p*100));o.put("status",p>=1?"done":"running");return "["+o.toString()+"]";}catch(Exception e){return "[]";}}
        @JavascriptInterface public String outputBase64(){try{File f=engine.getOutput();if(f==null||!f.exists())return "";return Base64.encodeToString(Files.readAllBytes(f.toPath()),Base64.NO_WRAP);}catch(Exception e){return "";}}
        @JavascriptInterface public void cancel(){engine.cancel();}
    }

    @Override public void onBackPressed(){ if(web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
