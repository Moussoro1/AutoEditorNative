package com.kabro.autoeditor;

import android.content.Context;
import android.util.Base64;
import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NativeRenderEngine {
    public interface Listener { void progress(float p); void done(File file); void error(String msg); }
    private final Context context; private final File workDir; private final Map<String,File> files=new ConcurrentHashMap<>();
    private volatile FFmpegSession session; private volatile File output; private volatile float progress; private volatile double total;
    public NativeRenderEngine(Context context){this.context=context.getApplicationContext();workDir=new File(context.getCacheDir(),"autoeditor_native");if(!workDir.exists())workDir.mkdirs();}
    public synchronized String putFile(String name,String base64)throws IOException{String safe=name.replaceAll("[^A-Za-z0-9._-]","_");File f=new File(workDir,safe);byte[] data=Base64.decode(base64,Base64.DEFAULT);try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}files.put(name,f);return f.getAbsolutePath();}
    public synchronized String start(String specJson,final Listener listener)throws Exception{
        if(session!=null)throw new IllegalStateException("A render is already running"); JSONObject spec=new JSONObject(specJson);JSONArray clips=spec.optJSONArray("clips");
        if(clips==null||clips.length()==0)throw new IllegalArgumentException("No clips in timeline");String audioName=spec.optString("audioName","audio");File audio=files.get(audioName);
        if(audio==null)for(Map.Entry<String,File> e:files.entrySet())if(e.getKey().equalsIgnoreCase(audioName)){audio=e.getValue();break;}
        if(audio==null)throw new FileNotFoundException("Audio file not found: "+audioName);int width=spec.optInt("width",1920),height=spec.optInt("height",1080),fps=spec.optInt("fps",30);total=0;
        for(int i=0;i<clips.length();i++)total=Math.max(total,clips.getJSONObject(i).optDouble("start",total)+clips.getJSONObject(i).optDouble("duration",0));if(total<=0)throw new IllegalArgumentException("Invalid timeline duration");
        StringBuilder cmd=new StringBuilder("-hide_banner -loglevel error -y ");StringBuilder filter=new StringBuilder();
        for(int i=0;i<clips.length();i++){JSONObject c=clips.getJSONObject(i);if(c.optBoolean("gap",false))cmd.append("-f lavfi -i color=c=black:s=").append(width).append("x").append(height).append(":r=").append(fps).append(" ");else{String name=c.optString("name","");File img=files.get(name);if(img==null)throw new FileNotFoundException("Image not found: "+name);double dur=c.optDouble("duration",1);cmd.append("-loop 1 -t ").append(fmt(dur+.05)).append(" -i ").append(q(img.getAbsolutePath())).append(" ");}double dur=c.optDouble("duration",1);filter.append("[").append(i).append(":v]scale=").append(width).append(":").append(height).append(":force_original_aspect_ratio=decrease,pad=").append(width).append(":").append(height).append(":(ow-iw)/2:(oh-ih)/2,setsar=1,fps=").append(fps).append(",format=yuv420p,trim=duration=").append(fmt(dur)).append(",setpts=PTS-STARTPTS[v").append(i).append("]; ");}
        boolean hasTransition=false;JSONArray transitions=spec.optJSONArray("transitions");double trDur=spec.optDouble("transitionDuration",.4);if(transitions!=null)for(int i=1;i<transitions.length();i++)if(!"cut".equalsIgnoreCase(transitions.optString(i,"cut"))&&!transitions.isNull(i)){hasTransition=true;break;}
        String videoLabel;if(!hasTransition){filter.append("concat=n=").append(clips.length()).append(":v=1:a=0[vout]");videoLabel="[vout]";}else{String current="[v0]";double offset=0;for(int i=1;i<clips.length();i++){String next="[v"+i+"]",out=i==clips.length()-1?"[vfinal]":"[vx"+i+"]",tr=transitions!=null?transitions.optString(i,"fade"):"fade";if(tr.isEmpty()||"cut".equalsIgnoreCase(tr)){filter.append(current).append(next).append("concat=n=2:v=1:a=0").append(out).append("; ");offset+=clips.getJSONObject(i-1).optDouble("duration",1);}else{double d=Math.min(trDur,Math.min(clips.getJSONObject(i-1).optDouble("duration",trDur),clips.getJSONObject(i).optDouble("duration",trDur)));offset+=clips.getJSONObject(i-1).optDouble("duration",1)-d;filter.append(current).append(next).append("xfade=transition=").append(mapTransition(tr)).append(":duration=").append(fmt(d)).append(":offset=").append(fmt(offset)).append(out).append("; ");offset+=d;}current=out;}videoLabel=current;}
        cmd.append("-i ").append(q(audio.getAbsolutePath())).append(" ");cmd.append("-filter_complex ").append(q(filter.toString().replace("; ",";"))).append(" ");cmd.append("-map ").append(videoLabel).append(" -map ").append(clips.length()).append(":a:0 -t ").append(fmt(total)).append(" -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 192k -shortest -movflags +faststart ");output=new File(workDir,"autoeditor-output.mp4");cmd.append(q(output.getAbsolutePath()));progress=0;final String command=cmd.toString();
        session=FFmpegKit.executeAsync(command,s->{session=null;if(ReturnCode.isSuccess(s.getReturnCode())&&output.exists()){progress=1f;listener.progress(1f);listener.done(output);}else listener.error("FFmpeg failed: "+(s.getFailStackTrace()==null?"unknown error":s.getFailStackTrace()));},log->{},statistics->{if(total>0&&statistics.getTime()>0){progress=Math.min(1f,(float)(statistics.getTime()/1000.0/total));listener.progress(progress);}});return "native-"+System.currentTimeMillis();}
    public void cancel(){FFmpegSession s=session;if(s!=null)FFmpegKit.cancel(s.getSessionId());}public float getProgress(){return progress;}public File getOutput(){return output;}
    private static String fmt(double d){return String.format(Locale.US,"%.3f",Math.max(.001,d));}private static String q(String s){return "'"+s.replace("'","'\\''")+"'";}private static String mapTransition(String t){switch(t.toLowerCase(Locale.US)){case "wipeleft":return "wipeleft";case "wiperight":return "wiperight";case "slideleft":return "slideleft";case "slideright":return "slideright";case "circleopen":return "circleopen";case "fadeblack":return "fadeblack";default:return "fade";}}
}
