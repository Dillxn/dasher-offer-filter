package com.local.dasherfilter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tips for the app's author, which is how Offer Filter is paid for instead of being sold. Nothing is charged from
 * here: a tip only opens Cash App, Venmo or PayPal with the author's name filled in, and the user pays there or not.
 * Nothing about it is counted or sent anywhere.
 */
final class Support {
    /** The author's own names on each service (public, not secrets); an empty one is not offered. */
    static final String CASH_APP = "JesusLovesYou0808";
    static final String VENMO = "dillonriecke";
    static final String PAYPAL = "";
    /** The names in use; tests stand in their own. */
    static String cashApp = CASH_APP;
    static String venmo = VENMO;
    static String payPal = PAYPAL;

    /** A service a tip can be left through. */
    enum Method {
        CASH_APP("Cash App"),
        VENMO("Venmo"),
        PAYPAL("PayPal");

        final String label;

        Method(String label) {
            this.label = label;
        }

        String handle() {
            switch (this) {
                case CASH_APP: return cashApp;
                case VENMO: return venmo;
                default: return payPal;
            }
        }
    }

    /** What each service accepts in a name, so a name can never change which address is opened. */
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9_-]{1,30}");

    /** The services with a usable name, in a fixed order; empty hides tipping altogether. */
    static List<Method> methods() {
        List<Method> methods = new ArrayList<>();
        for (Method method : Method.values()) {
            if (validHandle(method.handle())) methods.add(method);
        }
        return methods;
    }

    static boolean validHandle(String handle) {
        return handle != null && HANDLE.matcher(handle).matches();
    }

    /** The web address that opens {@code method} ready to pay the author; the service's app opens it if installed. */
    static String link(Method method) {
        return link(method, method.handle());
    }

    static String link(Method method, String handle) {
        if (!validHandle(handle)) throw new IllegalArgumentException(method + " has no usable name");
        switch (method) {
            case CASH_APP: return "https://cash.app/$" + handle;
            case VENMO: return "https://venmo.com/" + handle + "?txn=pay&note=Dash%20Buddy%20tip";
            default: return "https://paypal.me/" + handle;
        }
    }

    private Support() {}
}
