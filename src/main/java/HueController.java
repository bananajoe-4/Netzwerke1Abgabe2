import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class HueController {

    private static final int WAIT_TIME = 1000;
    private final String bridgeIpAddr;
    private String bridgeUsername = null;
    private final Map<Integer, Blinker> blinkingLamps = new HashMap<>();
    private static final int BLINK_PERIOD_DURATION = 500;

    public HueController(String bridgeIpAddr) {
        if (bridgeIpAddr == null || bridgeIpAddr.length() == 0)
            throw new IllegalArgumentException(bridgeIpAddr + " ist keine gueltige Ip Adresse.");
        this.bridgeIpAddr = bridgeIpAddr;
    }

    public HueController(String bridgeIpAddr, String bridgeUsername) {
        this(bridgeIpAddr);
        if (bridgeUsername == null || bridgeUsername.length() == 0)
            throw new IllegalArgumentException(bridgeUsername + " ist kein gueltiger username.");
        this.bridgeUsername = bridgeUsername;
    }

    public void setLampBlinkMode(int lampId, int color, int periodDuration){
        Blinker blinker = new Blinker(lampId, color, periodDuration);
        blinkingLamps.put(lampId, blinker);
        blinker.start();
    }

    public void setLampBlinkMode(int lampId, int color){
        setLampBlinkMode(lampId, color, BLINK_PERIOD_DURATION);
    }

    public void stopBlinkingLamp(int lampId){
        Blinker blinker = blinkingLamps.get(lampId);
        if(blinker != null)
            blinker.interrupt();
    }

    public void setLightState(int lampNr, boolean isOn, int color) throws IOException {
        if (color < 0 || color >= 1 << 16)
            throw new IllegalArgumentException("Color darf nur Werte im Bereich [0-65535] annehmen.");
        JsonObjectBuilder jsonBuilder = Json.createObjectBuilder()
                .add("on", isOn);
        // Die Farbe kann nur angepasst werden wenn die Lampe an ist.
        if (isOn)
            jsonBuilder.add("sat", 254)
                    .add("bri", 254)
                    .add("hue", color);

        applyLightState(lampNr, jsonBuilder.build().toString());
    }

    private void applyLightState(int lampNr, String stateJson) throws IOException {
        if (bridgeUsername == null)
            bridgeUsername = getBridgeUsername();

        HttpURLConnection connection = getBridgeConnection(lampNr);
        // request.
        try (OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());) {
            writer.write(stateJson);
        }

        // response. Koennte man auch wegschmeissen.
        try (JsonReader reader = Json.createReader(connection.getInputStream());) {
            JsonObject response = reader.readArray().getJsonObject(0);
        }
    }

    private String getBridgeUsername() throws IOException {
        String username = null;
        System.out.println("Um fortzufahren druecken sie bitte den Link Knopf ihrer Bridge.");
        while (username == null) {
            // Warten damit die Bridge nicht mit Http-Anfragen bombardiert wird.
            try {
                Thread.sleep(WAIT_TIME);
            } catch (InterruptedException e) {
                throw new AssertionError("Prozess wurde unerwartet unterbrochen.");
            }
            username = requestUsername();
        }

        return username;
    }

    private String requestUsername() throws IOException {
        String username = null;
        // Verbindung zur Bridge aufbauen.
        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) new URL("http://" + bridgeIpAddr + "/api").openConnection();
        } catch (MalformedURLException e) {
            throw new AssertionError("URL syntax ist ungueltig", e);
        }
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestMethod("POST");
        // Beantragen eines Usernames.
        try (JsonWriter writer = Json.createWriter(connection.getOutputStream());) {
            writer.writeObject(Json.createObjectBuilder().add("devicetype", "HueController#Anonymous").build());
        }
        try (JsonReader reader = Json.createReader(connection.getInputStream());) {
            JsonObject jsonObject = reader.readArray().getJsonObject(0);
            if (jsonObject.containsKey("success")) {
                username = jsonObject.getJsonObject("success").getJsonString("username").getString();
            }
        }
        return username;
    }

    private HttpURLConnection getBridgeConnection(int lampNr) {
        if (bridgeUsername == null)
            throw new AssertionError("getBridgeConnection darf erst aufgerufen werden wenn bridgeUsername ein gueltiger username its.");

        HttpURLConnection connection = null;
        try {
            final String bridgeAddr = "http://" + bridgeIpAddr + "/api/" + bridgeUsername + "/lights/" + lampNr + "/state";
            connection = (HttpURLConnection) new URL(bridgeAddr).openConnection();
            connection.setDoOutput(true);
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
        } catch (MalformedURLException e) {
            System.out.println("Bridge-Url ungültig.");
        } catch (IOException e) {
            System.out.println("Verbindung mit Bridge konnte nicht hergestellt werden.");
        }
        return connection;
    }


    private class Blinker extends Thread{
        private final int lampNr;
        private final int color;
        private final int periodDuration;
        private boolean isOn;

        private Blinker(int lampNr, int color, int periodDuration){
            this.lampNr = lampNr;
            this.color = color;
            this.periodDuration = periodDuration;
        }

        @Override
        public void run() {
            while(!isInterrupted()) {
                try {
                    setLightState(lampNr, isOn, color);
                } catch (IOException e) {
                    // Wir arbeiten nach dem best effort Prinzip und ignorieren daher alle moeglichen IOExceptions.
                }
                isOn = !isOn;
                blinkWait(periodDuration/2);
            }
        }

        private void blinkWait(int millis){
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                interrupt(); // Interrupt flag setzen damit der Thread die Schleife verlaesst.
            }
        }
    }
}
