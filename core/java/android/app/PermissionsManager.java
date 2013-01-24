package android.app;

import java.io.File;
import java.io.FileWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class PermissionsManager {
	
	Context mContext;
	
    private static final Object sSync = new Object[0];
    private static WriteThread writeThread;
    private static LinkedList<PermissionEvent> eventList = new LinkedList<PermissionEvent>();
    private static ConcurrentHashMap<Integer,String[]> knownUids = new ConcurrentHashMap<Integer,String[]>();
    
    private static ArrayList<String> pendingOutput;
    private static String parentFolder;
    private static String packageName;
    
    private static Random letsNotClobberTheSystem = new Random();
//    private static Globals sGlobals;
//
    static void initGlobals(Looper looper, Context context) {
        synchronized (sSync) {
//            if (sGlobals == null) {
//                sGlobals = new Globals(looper);
//            }
        	if (writeThread == null) {
        		writeThread = new WriteThread();
        		writeThread.start();
        	}
        	if (parentFolder == null) {
        		try {
        			parentFolder = context.getFilesDir().toString();
        		} catch (Exception e) {
        			e.printStackTrace();
        		}
        	}
        	if (packageName == null) {
        		try {
        			packageName = context.getPackageName();
        		} catch (Exception e) {
        			e.printStackTrace();
        		}
        	}
          
        }
    }
    
    private static class WriteThread extends Thread {
    	@Override
    	public void run() {
		while(true) { //always run. don't think there's any reason to die.
			try {
				Log.d("PermissionsManagerThread", "Starting write");
				startProcessingEvents();
				while (eventList.isEmpty() == false) {
					PermissionEvent event = eventList.removeFirst();
					processEvent(event);
				}
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				Log.d("PermissionsManagerThread", "Finishing write");
				finishProcessingEvents();
			}
			try {
				Thread.sleep(10000L+letsNotClobberTheSystem.nextInt(5000));
			} catch (InterruptedException e) {
				//do NOT care
			}
		}
    	}
    }
    
    public static class PermissionEvent {
    	String permission;
    	String message;
    	int uid;
    	boolean selfToo;
    	int resultOfCheck;
    	long time;
    	String[] packagenames;
		public PermissionEvent(String permission, String message, int uid,
				boolean selfToo, int resultOfCheck, long time,
				String[] packagenames) {
			super();
			this.permission = permission;
			this.message = message;
			this.uid = uid;
			this.selfToo = selfToo;
			this.resultOfCheck = resultOfCheck;
			this.time = time;
			this.packagenames = packagenames;
		}
		
		public JSONObject toJSON() throws JSONException {
			JSONObject out = new JSONObject();
			out.put("permission", permission);
			out.put("message", message);
			out.put("uid", uid);
			out.put("selfToo", selfToo);
			out.put("resultOfCheck", resultOfCheck);
			out.put("time", time);
			out.put("package-names", new JSONArray(Arrays.asList(packagenames)));
			return out;
		}
    	
    }

    /*package*/ PermissionsManager(Context context, Handler handler) {
        mContext = context;
        initGlobals(context.getMainLooper(), context);
    }
    
    
    public void addEvent(Context context, String permission, String message, int uid, boolean selfToo, int resultOfCheck) {
    	PermissionEvent event = new PermissionEvent(permission, message, uid, selfToo, resultOfCheck, System.currentTimeMillis(), getPackageNameForUid(uid, context));
    	eventList.add(event);
    }
    
    private String[] getPackageNameForUid(int uid, Context context) {
    	if (knownUids.containsKey(uid)) {
    		return knownUids.get(uid);
    	}
    	String[] packages = context.getPackageManager().getPackagesForUid(uid);
    	if (packages != null && packages.length >= 1) {
    		knownUids.put(uid, packages);
    	}
    	return packages;
    }
    
    
    private static void startProcessingEvents() {
    	if (pendingOutput == null) pendingOutput = new ArrayList<String>();
    	if (pendingOutput.size() > 1000) pendingOutput.clear();
    }
    
    private static void processEvent(PermissionEvent event) {
    	// Nooooo idea.
    	//Log.d("PermissionsManager", String.format("Logging: time %d permission:%s uid:%d, selftoo:%b result:%d packagname:%s message:%s", 
    	//		event.time, event.permission, event.uid, event.selfToo, event.resultOfCheck, Arrays.toString(event.packagenames), event.message));
    	try {
			pendingOutput.add(event.toJSON().toString(4));
		} catch (JSONException e) {
			e.printStackTrace();
		}
    }
    
    private static void finishProcessingEvents() {
        String internetStuff = getInternetStuff();
	if (parentFolder == null || (pendingOutput.size() <= 0 && internetStuff == null)) {
    		Log.d("PermissionsManager", "WRITEEVENTS Escaping. during write");
		return;
	}
    	try {
	    	File outdir = new File(parentFolder,"APM");
	    	outdir.setReadable(true, false);
	    	outdir.mkdirs();
	    	File outfile = new File(outdir, android.os.Process.myPid()+".json");
	    	outfile.setReadable(true, false);
	    	FileWriter writer = new FileWriter(outfile, true);
	    	for (String data : pendingOutput) writer.append(data);
                if (internetStuff != null) writer.append(internetStuff);
	    	writer.flush();
	    	writer.close();
    	} catch (Exception e) {
    		e.printStackTrace();
    	} finally {
    		Log.d("PermissionsManager", "WRITEEVENTS Clearing "+pendingOutput.size()+" entries"); 
    		pendingOutput.clear();
    	}
    }
    
    private static String getInternetStuff() {
       try {
           JSONObject obj = new JSONObject();
           obj.put("time", System.currentTimeMillis());
           obj.put("aatype", "internet"); 
           obj.put("package", packageName+"");
           String[] list = InetAddress.getAccessList();
           if (list == null || list.length == 0 || list[0] == null) return null;
           obj.put("access", new JSONArray(Arrays.asList(list)));
           InetAddress.clearAccessList();
           return obj.toString(4);
       } catch (Exception e) { e.printStackTrace(); }
       return null;

    }
	
}


