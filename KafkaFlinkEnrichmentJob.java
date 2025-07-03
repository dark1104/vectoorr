package com.example;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.json.JSONException;
import org.json.JSONObject;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import com.maxmind.geoip2.model.CountryResponse;

public class KafkaFlinkEnrichmentJob {
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(5000);

        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "192.168.91.37:9092");
        props.setProperty("group.id", "flink-java-consumer");

        FlinkKafkaConsumer<String> consumer = new FlinkKafkaConsumer<>("flows", new SimpleStringSchema(), props);
        DataStream<String> input = env.addSource(consumer);
        DataStream<String> enriched = input.map(new EnrichmentFunction());

        FlinkKafkaProducer<String> producer = new FlinkKafkaProducer<>(
            "enriched-flows",
            new SimpleStringSchema(),
            props
        );

        enriched.addSink(producer);
        env.execute("Kafka Flink Enrichment Java Job");
    }

    public static class EnrichmentFunction extends RichMapFunction<String, String> {
        private transient DatabaseReader asnReader;
        private transient DatabaseReader cityReader;
        private transient DatabaseReader countryReader;
        private transient Map<String, JSONObject> ipToMockData;
        private transient CloseableHttpClient httpClient;

        @Override
        public void open(Configuration parameters) throws Exception {
            asnReader = new DatabaseReader.Builder(new File("/opt/flink/geolite/GeoLite2-ASN.mmdb")).build();
            cityReader = new DatabaseReader.Builder(new File("/opt/flink/geolite/GeoLite2-City.mmdb")).build();
            countryReader = new DatabaseReader.Builder(new File("/opt/flink/geolite/GeoLite2-Country.mmdb")).build();
            ipToMockData = new HashMap<>();

            PoolingHttpClientConnectionManager connManager = new PoolingHttpClientConnectionManager();
            connManager.setMaxTotal(50);
            connManager.setDefaultMaxPerRoute(10);
            httpClient = HttpClients.custom().setConnectionManager(connManager).build();
        }

        @Override
        public String map(String value) throws Exception {
            JSONObject json;
            try {
                json = new JSONObject(value);
            } catch (JSONException e) {
                System.err.println("Invalid JSON input: " + value);
                return "{}";
            }

            String ipAddress = json.optString("sourceIPv4Address", "0.0.0.0").trim();
            Date now = new Date();
            json.put("date", new SimpleDateFormat("yyyy-MM-dd").format(now));
            json.put("time_inserted_ns", now.getTime() * 1_000_000);

            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
                Date flowStartTime = sdf.parse(json.getString("flow_start_time"));
                Date flowEndTime = sdf.parse(json.getString("flow_end_time"));
                json.put("time_received_ns", flowEndTime.getTime() * 1_000_000);
                json.put("flow_start_time", flowStartTime);
                json.put("flow_end_time", flowEndTime);
            } catch (ParseException e) {
                e.printStackTrace();
            }

            try {
                InetAddress ipAddressObj = InetAddress.getByName(ipAddress);
                AsnResponse asnResponse = asnReader.asn(ipAddressObj);
                CityResponse cityResponse = cityReader.city(ipAddressObj);
                CountryResponse countryResponse = countryReader.country(ipAddressObj);

                json.put("asn_number", asnResponse.getAutonomousSystemNumber());
                json.put("asn_organization", asnResponse.getAutonomousSystemOrganization());
                json.put("city_name", cityResponse.getCity().getName());
                json.put("country_name", countryResponse.getCountry().getName());
                json.put("latitude", cityResponse.getLocation().getLatitude());
                json.put("longitude", cityResponse.getLocation().getLongitude());
                json.put("iso_code", countryResponse.getCountry().getIsoCode());
            } catch (GeoIp2Exception | IOException e) {
                json.put("asn_number", 0);
                json.put("asn_organization", "NA");
                json.put("city_name", "NA");
                json.put("country_name", "NA");
                json.put("latitude", 0.0);
                json.put("longitude", 0.0);
                json.put("iso_code", "NA");
            }

            JSONObject apiData;

            if (ipToMockData.containsKey(ipAddress)) {
                apiData = ipToMockData.get(ipAddress);
            } else {
                try {
                    HttpPost post = new HttpPost("http://flask-api:5000/generate");
                    post.setHeader("Content-Type", "application/json");
                    post.setEntity(new StringEntity("{\"ip_address\": \"" + ipAddress + "\"}", ContentType.APPLICATION_JSON));

                    try (CloseableHttpResponse response = httpClient.execute(post)) {
                        String responseBody = EntityUtils.toString(response.getEntity());
                        apiData = new JSONObject(responseBody);
                        ipToMockData.put(ipAddress, apiData);
                    }
                } catch (IOException e) {
                    apiData = new JSONObject().put("error", "API call failed");
                }
            }

            for (String key : apiData.keySet()) {
                json.put(key, apiData.get(key));
            }

            return json.toString();
        }

        @Override
        public void close() throws Exception {
            if (httpClient != null) {
                httpClient.close();
            }
        }
    }
}