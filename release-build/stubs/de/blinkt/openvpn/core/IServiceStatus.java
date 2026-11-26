package de.blinkt.openvpn.core;
public interface IServiceStatus extends android.os.IInterface {
    void setCachedPassword(String uuid, int type, String password) throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IServiceStatus {
        public static native IServiceStatus asInterface(android.os.IBinder binder);
    }
}
