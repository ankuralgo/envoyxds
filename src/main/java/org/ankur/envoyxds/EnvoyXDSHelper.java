package org.ankur.envoyxds;


import com.google.gson.JsonObject;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import com.google.protobuf.InvalidProtocolBufferException;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.route.v3.*;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType.LOGICAL_DNS;
import static io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy.ROUND_ROBIN;
import static io.envoyproxy.envoy.config.core.v3.SocketAddress.Protocol.TCP_VALUE;


public class EnvoyXDSHelper {

    public static final int CONNECTION_TIMEOUT_DURATION = 25;
    public static final String CLUSTER_VERSION = "1";
    public static final String DEFAULT_TRANSPORT_SOCKET_NAME = "envoy.transport_sockets.tls";
    public static final String HTTPS = "HTTPS";
    public static final String DISCOVERY_RESPONSE_VERSION = "1";
    private static final String DEFAULT_ROUTE_NAME = "ENVOY_ROUTES";
    private final List<Route> routes = new ArrayList<>();
    private final List<Cluster> clusters = new ArrayList<>();


    public static EnvoyXDSHelper newBuilder() {
        return new EnvoyXDSHelper();
    }


    public String build() throws InvalidProtocolBufferException {
        String clustersString = fetchClusterConfig();
        String routesString = fetchRouteConfig();

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("clusters", clustersString);
        jsonObject.addProperty("routes", routesString);

        return jsonObject.toString();
    }

    public String addRouteAndCluster(String hostname, String prefix, String prefixRewrite, String hostRewriteLiteral) throws Exception {


        this.clusters.add(addCluster(hostname));

        RouteAction.Builder routeBuilder =
                RouteAction.newBuilder()
                        .setCluster(hostname)
                        .setTimeout(Duration.newBuilder().setSeconds(CONNECTION_TIMEOUT_DURATION));

        if (StringUtils.isNoneBlank(prefixRewrite)) {
            routeBuilder.setHostRewriteLiteral(prefixRewrite);
        }
        if (StringUtils.isNoneBlank(hostRewriteLiteral)) {
            routeBuilder.setPrefixRewrite(hostRewriteLiteral);
        }

        Route newRoute =
                Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder().setPrefix(prefix).build())
                        .setRoute(routeBuilder)
                        .build();

        this.routes.add(newRoute);
        return build();
    }

    @SneakyThrows
    private Cluster addCluster(String hostname) {

        URL url = new URL(hostname);

        int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();

        SocketAddress.Builder socketAddress =
                SocketAddress.newBuilder()
                        .setAddress(url.getHost())
                        .setPortValue(port)
                        .setProtocolValue(TCP_VALUE);

        Address.Builder address = Address.newBuilder().setSocketAddress(socketAddress);

        Endpoint.Builder endpoint = Endpoint.newBuilder().setAddress(address);

        LbEndpoint.Builder lbEndpoint = LbEndpoint.newBuilder().setEndpoint(endpoint);

        LocalityLbEndpoints.Builder lbEndpoints =
                LocalityLbEndpoints.newBuilder().addLbEndpoints(lbEndpoint);

        ClusterLoadAssignment.Builder loadAssignment =
                ClusterLoadAssignment.newBuilder()
                        .setClusterName(hostname)
                        .addEndpoints(lbEndpoints);

        Cluster.Builder clusterBuilder = Cluster.newBuilder();

        boolean isHTTPS = HTTPS.equalsIgnoreCase(url.getProtocol());
        if (isHTTPS) {
            UpstreamTlsContext upstreamTlsContext =
                    UpstreamTlsContext.newBuilder().setSni(url.getHost()).build();

            TransportSocket transportSocket =
                    TransportSocket.newBuilder()
                            .setName(DEFAULT_TRANSPORT_SOCKET_NAME)
                            .setTypedConfig(Any.pack(upstreamTlsContext))
                            .build();

            clusterBuilder.setTransportSocket(transportSocket);
        }

        Cluster cluster =
                clusterBuilder
                        .setName(hostname)
                        .setConnectTimeout(Duration.newBuilder().setSeconds(CONNECTION_TIMEOUT_DURATION))
                        .setLbPolicy(ROUND_ROBIN)
                        .setType(LOGICAL_DNS)
                        .setLoadAssignment(loadAssignment)
                        .build();

        return cluster;
    }


    public String fetchRouteConfig() throws InvalidProtocolBufferException {

        final VirtualHost.Builder virtualHostOrBuilder =
                VirtualHost.newBuilder().setName("MY_SERVER").addDomains("*");

        this.routes.forEach(virtualHostOrBuilder::addRoutes);

        VirtualHost virtualHost = virtualHostOrBuilder.build();

        RouteConfiguration routeConfiguration =
                RouteConfiguration.newBuilder()
                        .setName(DEFAULT_ROUTE_NAME)
                        .addVirtualHosts(virtualHost)
                        .build();

        DiscoveryResponse discoveryResponse =
                DiscoveryResponse.newBuilder()
                        .setVersionInfo(DISCOVERY_RESPONSE_VERSION)
                        .addResources(Any.pack(routeConfiguration))
                        .build();


        return discoveryResponse.toString();

    }

    private String fetchClusterConfig() throws InvalidProtocolBufferException {

        DiscoveryResponse.Builder discoveryResponseBuilder =
                DiscoveryResponse.newBuilder().setVersionInfo(CLUSTER_VERSION);

        this.clusters.forEach(cluster -> discoveryResponseBuilder.addResources(Any.pack(cluster)));

        DiscoveryResponse discoveryResponse = discoveryResponseBuilder.build();


        return discoveryResponse.toString();

    }


}
