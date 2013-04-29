/*
* aidl file : frameworks/base/core/java/android/os/IPermissionService.aidl
* This file contains definitions of functions which are exposed by service 
*/
package android.os;
interface IPermissionService {
/**
* {@hide}
*/

    oneway void postNewEvent(String permission, String message, int uid, boolean selfToo, int resultOfCheck, long time, String data);
    List<String> getEvents(String packagename);
    void uploadAllLogs();
    void clearAllLogs();
    void setUploadUrl(String url);
    String getUploadUrl();

    void setTimeUnits(long time);
    long getTimeUnits();
    
    void setUploadTime(long time);
    long getUploadTime();
    
    void setBacklogTime(int backlog);
    long getBacklogTime();
    
    void setEnableUpload(boolean enable);
    boolean getEnableUpload();

    List<String> getIgnoredPackages();
    void addToIgnoreList(String packagename);
    void removeFromIgnoreList(String packagename);

}
