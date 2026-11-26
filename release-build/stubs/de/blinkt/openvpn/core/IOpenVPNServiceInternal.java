package de.blinkt.openvpn.core;
public interface IOpenVPNServiceInternal extends android.os.IInterface {
    boolean stopVPN(boolean replaceConnection) throws android.os.RemoteException;
    abstract class Stub extends android.os.Binder implements IOpenVPNServiceInternal {
        public static native IOpenVPNServiceInternal asInterface(android.os.IBinder binder);
    }
}
