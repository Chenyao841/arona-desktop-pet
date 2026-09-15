package com.cy.utils;

public class CurrentHolder {
    private static final ThreadLocal<Integer> CURRENT_LOCAL = new ThreadLocal<>();
    public static void setCurrentID(Integer empId) {
        CURRENT_LOCAL.set(empId);
    }
    public static Integer getCurrentID() {
        return CURRENT_LOCAL.get();
    }
    public static void clear() {
        CURRENT_LOCAL.remove();
    }
}
