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
}
