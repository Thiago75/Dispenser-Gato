package com.dispensergato.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.text.*;
import java.util.*;

public class MainActivity extends Activity {
    private TextView status, nivel, historico, proximoHorario, conexao;
    private EditText brokerIp, brokerPorta, mqttUser, mqttPass, topicoConfig, topicoStatus, horario, tempoServo;
    private Button conectarBtn, salvarConfigBtn, alimentarBtn, salvarHorarioBtn, medirBtn;
    private final ArrayList<String> logs = new ArrayList<>();
    private MqttMiniClient mqtt;
    private Handler ui = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#F7F3EF"));

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(34, 38, 34, 34);
        root.setBackgroundColor(Color.parseColor("#F7F3EF"));
        scroll.addView(root);

        TextView title = txt("Dispenser Automático de Ração", 25, true);
        TextView sub = txt("Controle via EC2 + Mosquitto MQTT + ESP32", 15, false);
        sub.setTextColor(Color.parseColor("#6F6A65"));
        root.addView(title); root.addView(sub);

        root.addView(cardStatus());
        root.addView(cardControle());
        root.addView(cardMqtt());
        root.addView(cardHistorico());

        setContentView(scroll);
        carregarPrefs();
        addLog("App iniciado");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (mqtt != null) mqtt.disconnect();
    }

    private View cardStatus() {
        LinearLayout c = card();
        c.addView(txt("Status do dispenser", 18, true));
        conexao = txt("MQTT: desconectado", 15, false);
        status = txt("ESP32: aguardando status", 15, false);
        nivel = txt("Nível da ração: --%", 15, false);
        proximoHorario = txt("Próximo horário: 08:00", 15, false);
        c.addView(conexao); c.addView(status); c.addView(nivel); c.addView(proximoHorario);
        medirBtn = btn("Pedir leitura do sensor");
        medirBtn.setOnClickListener(v -> publicarJson("{\"comando\":\"MEDIR_NIVEL\"}", "Pedido de medição enviado"));
        c.addView(medirBtn);
        return c;
    }

    private View cardControle() {
        LinearLayout c = card();
        c.addView(txt("Controle de alimentação", 18, true));
        horario = input("Horário de alimentação. Ex: 08:00");
        tempoServo = input("Tempo do servo aberto em ms. Ex: 2000");
        c.addView(label("Novo horário")); c.addView(horario);
        c.addView(label("Quantidade / tempo de abertura")); c.addView(tempoServo);

        salvarHorarioBtn = btn("Salvar horário no ESP32");
        salvarHorarioBtn.setOnClickListener(v -> {
            String h = horario.getText().toString().trim();
            if (!h.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) { toast("Use o formato HH:MM"); return; }
            String json = "{\"novo_horario\":\"" + h + "\",\"tempo_abertura\":" + safeTempo() + "}";
            getPreferences(0).edit().putString("horario", h).putString("tempo", tempoServo.getText().toString()).apply();
            proximoHorario.setText("Próximo horário: " + h);
            publicarJson(json, "Horário enviado ao ESP32");
        });

        alimentarBtn = btn("Alimentar agora");
        alimentarBtn.setOnClickListener(v -> publicarJson("{\"comando\":\"ALIMENTAR_AGORA\"}", "Comando ALIMENTAR_AGORA enviado"));
        c.addView(salvarHorarioBtn); c.addView(alimentarBtn);
        return c;
    }

    private View cardMqtt() {
        LinearLayout c = card();
        c.addView(txt("Configuração MQTT / EC2", 18, true));
        brokerIp = input("IP público ou DNS da EC2");
        brokerPorta = input("Porta. Ex: 1883");
        mqttUser = input("Usuário Mosquitto, se tiver");
        mqttPass = input("Senha Mosquitto, se tiver");
        mqttPass.setInputType(0x00000081);
        topicoConfig = input("Tópico de comando/configuração");
        topicoStatus = input("Tópico de status/histórico");

        c.addView(label("Broker / EC2")); c.addView(brokerIp);
        c.addView(label("Porta")); c.addView(brokerPorta);
        c.addView(label("Usuário")); c.addView(mqttUser);
        c.addView(label("Senha")); c.addView(mqttPass);
        c.addView(label("Publicar comandos em")); c.addView(topicoConfig);
        c.addView(label("Ler status de")); c.addView(topicoStatus);

        conectarBtn = btn("Conectar ao Mosquitto");
        conectarBtn.setOnClickListener(v -> conectarMqtt());
        salvarConfigBtn = btn("Salvar configurações MQTT");
        salvarConfigBtn.setOnClickListener(v -> salvarPrefs());
        c.addView(salvarConfigBtn); c.addView(conectarBtn);

        TextView obs = txt("Obs.: libere a porta 1883 no Security Group da EC2 e deixe o Mosquitto aceitando conexões externas. Use usuário/senha se o broker exigir.", 13, false);
        obs.setTextColor(Color.parseColor("#7A4E12"));
        c.addView(obs);
        return c;
    }

    private View cardHistorico() {
        LinearLayout c = card();
        c.addView(txt("Histórico", 18, true));
        historico = txt("", 14, false);
        c.addView(historico);
        return c;
    }

    private void conectarMqtt() {
        salvarPrefs();
        String host = brokerIp.getText().toString().trim();
        int port;
        try { port = Integer.parseInt(brokerPorta.getText().toString().trim()); }
        catch (Exception e) { toast("Porta inválida"); return; }
        if (host.length() == 0) { toast("Preencha o IP/DNS da EC2"); return; }

        conexao.setText("MQTT: conectando...");
        addLog("Conectando ao broker " + host + ":" + port);

        new Thread(() -> {
            try {
                if (mqtt != null) mqtt.disconnect();
                mqtt = new MqttMiniClient(host, port, mqttUser.getText().toString().trim(), mqttPass.getText().toString(), message -> {
                    ui.post(() -> tratarStatus(message));
                });
                mqtt.connect();
                mqtt.subscribe(topicoStatus.getText().toString().trim());
                ui.post(() -> {
                    conexao.setText("MQTT: conectado ao Mosquitto");
                    status.setText("ESP32: aguardando mensagens");
                    addLog("Conectado e inscrito em " + topicoStatus.getText().toString().trim());
                    toast("Conectado ao Mosquitto");
                });
                mqtt.listenLoop();
            } catch (Exception e) {
                ui.post(() -> {
                    conexao.setText("MQTT: erro na conexão");
                    addLog("Erro MQTT: " + e.getMessage());
                    toast("Erro MQTT: " + e.getMessage());
                });
            }
        }).start();
    }

    private void publicarJson(String json, String logOk) {
        if (mqtt == null || !mqtt.isConnected()) { toast("Conecte ao Mosquitto primeiro"); return; }
        String topic = topicoConfig.getText().toString().trim();
        new Thread(() -> {
            try {
                mqtt.publish(topic, json);
                ui.post(() -> { addLog(logOk + ": " + json); toast("Comando enviado"); });
            } catch (Exception e) {
                ui.post(() -> { addLog("Erro ao publicar: " + e.getMessage()); toast("Erro ao publicar"); });
            }
        }).start();
    }

    private void tratarStatus(String payload) {
        addLog("Status recebido: " + payload);
        status.setText("ESP32: online / mensagem recebida");
        String evento = extrairJsonString(payload, "evento");
        String wifi = extrairJsonString(payload, "wifi");
        String prox = extrairJsonString(payload, "proximo_horario");
        String n = extrairJsonNumero(payload, "nivel");
        if (evento != null) status.setText("ESP32: " + evento + (wifi != null ? " | Wi-Fi " + wifi : ""));
        if (n != null) nivel.setText("Nível da ração: " + n + "%");
        if (prox != null) proximoHorario.setText("Próximo horário: " + prox);
    }

    private String extrairJsonString(String json, String key) {
        try {
            String p = "\"" + key + "\"";
            int i = json.indexOf(p); if (i < 0) return null;
            int c = json.indexOf(':', i); int q1 = json.indexOf('"', c + 1); int q2 = json.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) return null;
            return json.substring(q1 + 1, q2);
        } catch(Exception e) { return null; }
    }
    private String extrairJsonNumero(String json, String key) {
        try {
            String p = "\"" + key + "\"";
            int i = json.indexOf(p); if (i < 0) return null;
            int c = json.indexOf(':', i); int end = c + 1;
            while (end < json.length() && " -0123456789.".indexOf(json.charAt(end)) >= 0) end++;
            return json.substring(c + 1, end).trim();
        } catch(Exception e) { return null; }
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(26, 24, 26, 24);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 24, 0, 0);
        c.setLayoutParams(lp);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE); bg.setCornerRadius(28); bg.setStroke(1, Color.parseColor("#E6DDD4"));
        c.setBackground(bg);
        return c;
    }

    private TextView txt(String s, int sp, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(Color.parseColor("#27221F")); t.setPadding(0, 6, 0, 6); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }
    private TextView label(String s) { TextView t = txt(s, 13, true); t.setTextColor(Color.parseColor("#6F6A65")); return t; }
    private EditText input(String hint) { EditText e = new EditText(this); e.setHint(hint); e.setSingleLine(true); e.setTextSize(14); e.setPadding(14, 8, 14, 8); return e; }
    private Button btn(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private String safeTempo() { String t = tempoServo.getText().toString().trim(); return t.matches("\\d+") ? t : "2000"; }
    private void addLog(String s) { String h = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()); logs.add(0, h + " — " + s); StringBuilder sb = new StringBuilder(); for (int i=0;i<Math.min(14, logs.size());i++) sb.append("• ").append(logs.get(i)).append("\n"); if (historico != null) historico.setText(sb.toString()); }

    private void salvarPrefs() {
        getPreferences(0).edit()
            .putString("brokerIp", brokerIp.getText().toString())
            .putString("brokerPorta", brokerPorta.getText().toString())
            .putString("mqttUser", mqttUser.getText().toString())
            .putString("mqttPass", mqttPass.getText().toString())
            .putString("topicoConfig", topicoConfig.getText().toString())
            .putString("topicoStatus", topicoStatus.getText().toString())
            .putString("horario", horario.getText().toString())
            .putString("tempo", tempoServo.getText().toString()).apply();
        addLog("Configurações MQTT salvas");
    }

    private void carregarPrefs() {
        SharedPreferences p = getPreferences(0);
        brokerIp.setText(p.getString("brokerIp", "SEU_IP_PUBLICO_EC2"));
        brokerPorta.setText(p.getString("brokerPorta", "1883"));
        mqttUser.setText(p.getString("mqttUser", ""));
        mqttPass.setText(p.getString("mqttPass", ""));
        topicoConfig.setText(p.getString("topicoConfig", "dispenser/config"));
        topicoStatus.setText(p.getString("topicoStatus", "dispenser/status"));
        horario.setText(p.getString("horario", "08:00"));
        tempoServo.setText(p.getString("tempo", "2000"));
        proximoHorario.setText("Próximo horário: " + horario.getText().toString());
    }

    interface MessageCallback { void onMessage(String payload); }

    static class MqttMiniClient {
        private final String host, user, pass;
        private final int port;
        private final MessageCallback callback;
        private Socket socket;
        private InputStream in;
        private OutputStream out;
        private volatile boolean connected = false;
        private final String clientId = "AndroidDispenser" + System.currentTimeMillis();

        MqttMiniClient(String host, int port, String user, String pass, MessageCallback cb) {
            this.host = host; this.port = port; this.user = user; this.pass = pass; this.callback = cb;
        }

        boolean isConnected() { return connected && socket != null && socket.isConnected() && !socket.isClosed(); }

        void connect() throws Exception {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 7000);
            socket.setSoTimeout(0);
            in = socket.getInputStream(); out = socket.getOutputStream();

            ByteArrayOutputStream vh = new ByteArrayOutputStream();
            writeUtf(vh, "MQTT"); vh.write(4); // MQTT 3.1.1
            int flags = 0x02; // clean session
            if (user != null && user.length() > 0) flags |= 0x80;
            if (pass != null && pass.length() > 0) flags |= 0x40;
            vh.write(flags); vh.write(0); vh.write(60); // keep alive 60s
            writeUtf(vh, clientId);
            if (user != null && user.length() > 0) writeUtf(vh, user);
            if (pass != null && pass.length() > 0) writeUtf(vh, pass);

            byte[] body = vh.toByteArray();
            out.write(0x10); writeRemainingLength(out, body.length); out.write(body); out.flush();

            int type = in.read(); if (type != 0x20) throw new IOException("CONNACK inválido");
            readRemainingLength(in);
            int sp = in.read(); int rc = in.read();
            if (rc != 0) throw new IOException("Broker recusou conexão. Código " + rc);
            connected = true;
        }

        void subscribe(String topic) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(0); body.write(1); // packet id
            writeUtf(body, topic); body.write(0); // QoS 0
            byte[] data = body.toByteArray();
            out.write(0x82); writeRemainingLength(out, data.length); out.write(data); out.flush();
            // SUBACK may be read by listenLoop; okay if not consumed here.
        }

        void publish(String topic, String payload) throws Exception {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeUtf(body, topic);
            body.write(payload.getBytes("UTF-8"));
            byte[] data = body.toByteArray();
            out.write(0x30); writeRemainingLength(out, data.length); out.write(data); out.flush();
        }

        void listenLoop() throws Exception {
            while (isConnected()) {
                int header = in.read();
                if (header < 0) throw new IOException("Conexão encerrada");
                int type = header & 0xF0;
                int len = readRemainingLength(in);
                byte[] data = readExact(in, len);
                if (type == 0x30) { // publish QoS 0
                    int tlen = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                    int pos = 2 + tlen;
                    if (pos <= data.length) {
                        String payload = new String(data, pos, data.length - pos, "UTF-8");
                        callback.onMessage(payload);
                    }
                }
            }
        }

        void disconnect() {
            connected = false;
            try { if (out != null) { out.write(0xE0); out.write(0); out.flush(); } } catch(Exception ignored) {}
            try { if (socket != null) socket.close(); } catch(Exception ignored) {}
        }

        private static void writeUtf(ByteArrayOutputStream out, String s) throws IOException {
            byte[] b = s.getBytes("UTF-8");
            out.write((b.length >> 8) & 0xFF); out.write(b.length & 0xFF); out.write(b);
        }
        private static void writeRemainingLength(OutputStream out, int len) throws IOException {
            do { int digit = len % 128; len /= 128; if (len > 0) digit |= 128; out.write(digit); } while (len > 0);
        }
        private static int readRemainingLength(InputStream in) throws IOException {
            int multiplier = 1, value = 0, digit;
            do { digit = in.read(); if (digit < 0) throw new EOFException(); value += (digit & 127) * multiplier; multiplier *= 128; } while ((digit & 128) != 0);
            return value;
        }
        private static byte[] readExact(InputStream in, int len) throws IOException {
            byte[] data = new byte[len]; int pos = 0;
            while (pos < len) { int r = in.read(data, pos, len-pos); if (r < 0) throw new EOFException(); pos += r; }
            return data;
        }
    }
}
