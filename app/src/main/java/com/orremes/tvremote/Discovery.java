package com.orremes.tvremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.Inet4Address;
import java.net.InetAddress;

/**
 * Looks for Android TV boxes on the home network (mDNS service _androidtvremote2._tcp)
 * and reports the address of the first one found.
 */
final class Discovery {
    interface Callback {
        void onFound(String host);

        void onFail(String message);
    }

    private final NsdManager nsd;
    private final Callback callback;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean finished;

    Discovery(Context context, Callback callback) {
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        this.callback = callback;
    }

    synchronized void start() {
        finished = false;
        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                fail("החיפוש האוטומטי לא התחיל (שגיאה " + errorCode + ")");
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) {}
            @Override public void onDiscoveryStarted(String serviceType) {}
            @Override public void onDiscoveryStopped(String serviceType) {}
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) {}

            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {}

                    @Override public void onServiceResolved(NsdServiceInfo info) {
                        InetAddress address = info.getHost();
                        if (address instanceof Inet4Address) {
                            succeed(address.getHostAddress());
                        }
                    }
                });
            }
        };
        try {
            nsd.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        } catch (RuntimeException e) {
            fail("החיפוש האוטומטי לא זמין: " + e.getMessage());
        }
    }

    private synchronized void succeed(String host) {
        if (finished) {
            return;
        }
        finished = true;
        stop();
        callback.onFound(host);
    }

    private synchronized void fail(String message) {
        if (finished) {
            return;
        }
        finished = true;
        callback.onFail(message);
    }

    /** Called when the search runs out of time. */
    synchronized void timeout() {
        if (finished) {
            return;
        }
        finished = true;
        stop();
        callback.onFail("לא נמצא ממיר ברשת. אפשר להקליד את כתובת ה-IP שלו ידנית.");
    }

    synchronized void stop() {
        if (discoveryListener != null) {
            try {
                nsd.stopServiceDiscovery(discoveryListener);
            } catch (RuntimeException ignored) {
                // not running
            }
            discoveryListener = null;
        }
    }
}
