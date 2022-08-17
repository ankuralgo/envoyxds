package org.ankur.envoyxds;

public class Main {
    public static void main(String[] args) throws Exception {

        EnvoyXDSHelper helper = new EnvoyXDSHelper();
        String config = helper.addRouteAndCluster("https://google.com", "/go", "/", "google.com");
        System.out.println(config);


    }
}