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

public class PermissionsManager {
    
    Context mContext;
    
    private static final Object sSync = new Object[0];
    private static WriteThread writeThread;
    private static ConcurrentLinkedQueue<PermissionEvent> eventList = new ConcurrentLinkedQueue<PermissionEvent>();
    private static ConcurrentHashMap<Integer,String[]> knownUids = new ConcurrentHashMap<Integer,String[]>();
    
    private static ArrayList<String> pendingOutput;
    private static String parentFolder;
    private static IPermissionService permService;
    
    static long lastwrite = 0L;
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
    
    private static Random letsNotClobberTheSystem = new Random();
//    private static Globals sGlobals;
//
    protected static void touch() {
    synchronized (sSync) {
        AbstractHttpClient.executeListener = httpListener;
        URL.executeListener = urlListener;
                //permService = IPermissionService.Stub.asInterface(ServiceManager.getService("Permission"));
        System.out.println("Set up this PID's permission listeners");
        }
    }
    public static void initGlobals(Context context) {
        synchronized (sSync) {
//            if (sGlobals == null) {
//                sGlobals = new Globals(looper);
//            }
                try {
            checkWriteThread();
            if (parentFolder == null) {
                try {
                    parentFolder = context.getFilesDir().toString();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            try {
                getPackageNameForUid(Process.myUid(), context);
            } catch (Exception e) {
                e.printStackTrace();
            }
            AbstractHttpClient.executeListener = httpListener;
            
            permService = IPermissionService.Stub.asInterface(ServiceManager.getService("Permission"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static IPermissionService getPermService() {
        if (permService != null) return permService;

        permService = IPermissionService.Stub.asInterface(ServiceManager.getService("Permission"));
        return permService;
    }

    private static void checkWriteThread() {
        if (writeThread == null) {
            writeThread = new WriteThread();
            writeThread.start();
        }
    }
    
    private static class WriteThread extends Thread {
        boolean longsleep = false;
        long lastsleep = 0;
        @Override
        public void run() {
            while(true) { //always run. don't think there's any reason to die.
                longsleep = true;
                //if (SystemClock.uptimeMillis() - lastwrite > 1000*10) { //if we haven't written in 10 seconds
                    try {
                        Log.d("PermissionsManagerThread", "Starting write");
                        startProcessingEvents();

                        PermissionEvent event = null;
                        while ((event = eventList.poll()) != null) {
                            longsleep = false;
                            processEvent(event);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        Log.d("PermissionsManagerThread", "Finishing write");
                        finishProcessingEvents();
                        lastwrite = SystemClock.uptimeMillis();
                    }
                //} else {
                //  longsleep = false;
                //}
                try {
                    if (longsleep) Thread.sleep(1000*1000); //a  really long time
                    else Thread.sleep(500L);
                } catch (InterruptedException e) {
                    //do NOT care
                }
            }
        }
    }
    
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

    /*package*/ PermissionsManager(Context context, Handler handler) {
        mContext = context;
        initGlobals(context);
    }
    
    public static void addEvent(Context context, String permission, String message, int uid, boolean selfToo, int resultOfCheck) {
        addEvent(context,permission,message,uid,selfToo,resultOfCheck, null);
    }
    
    public static void addEvent(Context context, String permission, String message, int uid, boolean selfToo, int resultOfCheck, String data) {
        try {
            getPermService().postNewEvent(permission, message, uid, selfToo, resultOfCheck, System.currentTimeMillis(), data);
        } catch (Exception e) { e.printStackTrace(); }
        PermissionEvent event = new PermissionEvent(permission, message, uid, selfToo, resultOfCheck, System.currentTimeMillis(), getPackageNameForUid(uid, context), data);
        //getPermService().postEvent(event.toJSON().toString());
        eventList.add(event);
        checkWriteThread();
        writeThread.interrupt();
    }
    
    private static String[] getPackageNameForUid(int uid, Context context) {
        if (knownUids.containsKey(uid)) {
            return knownUids.get(uid);
        }
        if (context != null) {
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages != null && packages.length >= 1) {
            knownUids.put(uid, packages);
        }
        return packages;
        } else {
                return new String[] { "unknown" };
        }
    }
    
    
    private static void startProcessingEvents() {
        if (pendingOutput == null) pendingOutput = new ArrayList<String>();
        if (pendingOutput.size() > 1000) pendingOutput.clear();
    }
    
    private static void processEvent(PermissionEvent event) {
        // Nooooo idea.
        //Log.d("PermissionsManager", String.format("Logging: time %d permission:%s uid:%d, selftoo:%b result:%d packagname:%s message:%s", 
        //      event.time, event.permission, event.uid, event.selfToo, event.resultOfCheck, Arrays.toString(event.packagenames), event.message));
        try {
            pendingOutput.add(event.toJSON().toString(4));
            Log.d("PermissionsManager", "Calling into global service");
            getPermService().postEvent(event.toJSON().toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void finishProcessingEvents() {
        //String internetStuff = getInternetStuff();
    if (parentFolder == null || pendingOutput.size() <= 0) {
            Log.d("PermissionsManager", "WRITEEVENTS Escaping. during write");
        return;
    }
        try {
            File outdir = new File(parentFolder,"APM");
            outdir.setReadable(true, false);
            outdir.mkdirs();
            File outfile = new File(outdir, android.os.Process.myUid()+".json");
            outfile.setReadable(true, false);
            FileWriter writer = new FileWriter(outfile, true);
            for (String data : pendingOutput) writer.append(data);
                //if (internetStuff != null) writer.append(internetStuff);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Log.d("PermissionsManager", "WRITEEVENTS Clearing "+pendingOutput.size()+" entries"); 
            pendingOutput.clear();
        }
    }
    /* 
    private static String getInternetStuff() {
       try {
           JSONObject obj = new JSONObject();
           obj.put("time", System.currentTimeMillis());
           obj.put("aatype", "internet"); 
           obj.put("package", packageName+"");
           String[] list = null;//InetAddress.getAccessList();
           if (list == null || list.length == 0 || list[0] == null) return null;
           obj.put("access", new JSONArray(Arrays.asList(list)));
           //InetAddress.clearAccessList();
           return obj.toString(4);
       } catch (Exception e) { e.printStackTrace(); }
       return null;

    }
    */
    
}


