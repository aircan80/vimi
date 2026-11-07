package com.vimi.gui;

public final class OneScratch {
    public static void main(String[] args) throws Exception {
        var m = WorldStreamSourceControllerTest.class.getDeclaredMethod(System.getProperty("one.method"));
        m.setAccessible(true);
        int n = Integer.getInteger("one.n", 20), fails = 0;
        for (int i = 0; i < n; i++) {
            try { m.invoke(null); } catch (java.lang.reflect.InvocationTargetException e) {
                fails++; System.out.println("FAIL " + i + " " + String.valueOf(e.getCause()).substring(0, 160));
            }
        }
        System.out.println("fails=" + fails + "/" + n);
        System.exit(0);
    }
}
