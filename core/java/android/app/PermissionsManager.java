package android.app;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URL.URLExecuteListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Random;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.io.ByteArrayOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.apache.http.impl.client.AbstractHttpClient;
import org.apache.http.*;
import org.apache.http.protocol.*;
import org.apache.http.impl.client.AbstractHttpClient.ExecuteListener;
import org.apache.http.client.entity.*;

import android.content.Context;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.os.ServiceManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.os.IPermissionService;
import android.media.AudioRecord;
import android.media.AudioRecord.AudioRecordListener;
import android.media.MediaRecorder;
import android.media.MediaRecorder.MediaRecorderListener;
import android.hardware.Camera;
import android.hardware.Camera.CameraListener;

public class PermissionsManager {

    public static class PermissionEvent {
        public String permission;
        public String message;
        public int uid;
        public boolean selfToo;
        public int resultOfCheck;
        public long time;
        public String data;
        public String[] packagenames;

        public PermissionEvent(String permission, String message, int uid,
                boolean selfToo, int resultOfCheck, long time,
                String[] packagenames, String data) {
            super();
            this.permission = permission;
            this.message = message;
            this.uid = uid;
            this.selfToo = selfToo;
            this.resultOfCheck = resultOfCheck;
            this.time = time;
            this.packagenames = packagenames;
                    this.data = data;
        }
        
        public JSONObject toJSON() throws JSONException {
            JSONObject out = new JSONObject();
            out.put("permission", permission);
            out.put("message", message);
            out.put("uid", uid);
            out.put("selfToo", selfToo);
            out.put("resultOfCheck", resultOfCheck);
            out.put("time", time);
            out.put("data", data == null ? "" : data);
            if (packagenames == null || packagenames.length == 0) {
                out.put("package-names", new JSONArray());
            } else {
                out.put("package-names", new JSONArray(Arrays.asList(packagenames)));
            }
            return out;
        }
        public static PermissionEvent fromJSON(String jsonString) throws JSONException {
            JSONObject obj = new JSONObject(jsonString);
            PermissionEvent evt = new PermissionEvent(null, null, 0, false, 0, 0, null, null);
            evt.permission = obj.getString("permission");
            try {
                evt.message = obj.getString("message");
            } catch (Exception e) {
                evt.message= "";
            }
            evt.uid = obj.getInt("uid");
            evt.selfToo = obj.getBoolean("selfToo");
            evt.resultOfCheck = obj.getInt("resultOfCheck");
            evt.time = obj.getLong("time");
            try {
                evt.data = obj.getString("data");
            } catch (Exception e) {
                evt.data = "";
            }

            try {
                ArrayList<String>packages = new ArrayList<String>();
                JSONArray jpackages = obj.getJSONArray("package-names");
                for (int i=0; i<jpackages.length(); i++) {
                    packages.add(jpackages.getString(i));
                }
                evt.packagenames = packages.toArray(new String[0]);
            } catch (Exception e) {
                evt.packagenames = new String[0];
            }
            return evt;
        }
        
    }
    


    
    private static final Object sSync = new Object[0];
    private static IPermissionService permService;
    
//    static long lastwrite = 0L;
    public static String convertStreamToString(java.io.InputStream is) {
        java.util.Scanner s = new java.util.Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }
    private static ExecuteListener httpListener = new ExecuteListener() {
        public void onExecute(HttpHost target, HttpRequest request, HttpContext context) {
            Log.d("PermissionsManager", "onExecute: http: "+request.getRequestLine().toString());
            String data = "";
            if (request instanceof HttpEntityEnclosingRequest) {
                HttpEntityEnclosingRequest message = (HttpEntityEnclosingRequest)request;
                //Header[] headers = message.getHeaders();
                //HttpParams params = message.getParams();
                HttpEntity entity = message.getEntity();
                //if (entity instanceof UrlEncodedFormEntity) {
                    //UrlEncodedFormEntity urlent = (UrlEncodedFormEntity)entity;
                if (entity.isRepeatable()) {
                    HttpEntity urlent = entity;
                    try {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        urlent.writeTo(baos);
                        String header = baos.toString();
                        //String header = convertStreamToString(urlent.getContent());
                        Log.d("PermissionsManager", "onExecute: UrlEncodedEntity: "+header);
                        data = header;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                
            }
            addEvent(null, "internet.http.client", request.getRequestLine().toString(), Process.myUid(), true, 0, data);
            
        }
    }; 
    private static URLExecuteListener urlListener = new URLExecuteListener() {
        public void onExecute(URL url) {
            if ("http".equals(url.getProtocol()) || "https".equals(url.getProtocol()) ) {
                Log.d("PermissionsManager", "onExecute: url: "+url.toString());
                addEvent(null, "internet.http.url", url.toString(), Process.myUid(), true, 0);
            }
        }
    }; 
    private static AudioRecordListener audioListener = new AudioRecordListener() {
        public void onInit() {
            addEvent(null, "android.permission.RECORD_AUDIO", "init", Process.myUid(), true, 0);
        }
        public void onStart() {
            addEvent(null, "android.permission.RECORD_AUDIO", "start", Process.myUid(), true, 0);
        }
        public void onStop() {
            addEvent(null, "android.permission.RECORD_AUDIO", "stop", Process.myUid(), true, 0);
        }
    };
    private static MediaRecorderListener mediaListener = new MediaRecorderListener() {
        public void onInitAudio() {
            addEvent(null, "android.permission.RECORD_AUDIO", "init", Process.myUid(), true, 0);
        }
        public void onInitVideo() {
            addEvent(null, "android.permission.CAMERA", "init", Process.myUid(), true, 0);
        }
        public void onStart() {
            addEvent(null, "android.permission.RECORD_AUDIO/VIDEO", "start", Process.myUid(), true, 0);
        }
        public void onStop() {
            addEvent(null, "android.permission.RECORD_AUDIO/VIDEO", "stop", Process.myUid(), true, 0);
        }
    };
    private static CameraListener cameraListener = new CameraListener() {
        public void onOpen() {
            addEvent(null, "android.permission.CAMERA", "open", Process.myUid(), true, 0);
        }
    };
    
//    private static Random letsNotClobberTheSystem = new Random();
//    private static Globals sGlobals;
//
    protected static void touch() {
        synchronized (sSync) {
            AbstractHttpClient.executeListener = httpListener;
            URL.executeListener = urlListener;
            AudioRecord.listener = audioListener;
            Camera.listener = cameraListener;
            MediaRecorder.listener = mediaListener;
                    //permService = IPermissionService.Stub.asInterface(ServiceManager.getService("Permission"));
            System.out.println("Set up this PID's permission listeners");
        }
    }
    public static void initGlobals(Context context) {
        synchronized (sSync) {
            try {
                AbstractHttpClient.executeListener = httpListener;
                
                permService = IPermissionService.Stub.asInterface(ServiceManager.getService("Permission"));
                sendOnInit();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        touch();
    }

    private static IPermissionService getPermService() {
        if (permService != null) return permService;

        permService = IPermissionService.Stub.asInterface(ServiceManager.getService("Permission"));
        return permService;
    }

    public static void addEvent(Context context, String permission, String message, int uid, boolean selfToo, int resultOfCheck) {
        addEvent(context,permission,message,uid,selfToo,resultOfCheck, "");
    }
    
    public static void addEvent(Context context, String permission, String message, int uid, boolean selfToo, int resultOfCheck, String data) {
        try {
            getPermService().postNewEvent(permission, message, uid, selfToo, resultOfCheck, System.currentTimeMillis(), data);
        } catch (Exception e) { e.printStackTrace(); }
    }



    
    Context mContext;

    /*package*/ PermissionsManager(Context context, Handler handler) {
        mContext = context;
        initGlobals(context);
    }


    private static void sendOnInit() {
        addEvent(null, "android.activity.ACTION", "init", Process.myUid(), true, 0);
    }
    private void sendOnForeground() {
        addEvent(null, "android.activity.ACTION", "foreground", Process.myUid(), true, 0);
    }
    private void sendOnBackground() {
        addEvent(null, "android.activity.ACTION", "background", Process.myUid(), true, 0);
    }

    public List<String> getRawEvents(String packagename) {
        try {
            return getPermService().getEvents(packagename);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }


    //public JSONArray get
    
}


