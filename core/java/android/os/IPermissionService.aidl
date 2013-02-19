/*
* aidl file : frameworks/base/core/java/android/os/IPermissionService.aidl
* This file contains definitions of functions which are exposed by service 
*/
package android.os;
interface IPermissionService {
/**
* {@hide}
*/
	void postEvent(String jsonEvent);
}
