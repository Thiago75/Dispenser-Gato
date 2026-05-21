#include <WiFi.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <ESP32Servo.h>
#include <time.h>
#include <Wire.h> 
#include <LiquidCrystal_I2C.h> 

// ================= WIFI =================
const char* ssid = "NOME_WIFI"; 
const char* password = "SENHA_WIFI"; 

// ================= MQTT (EC2 + MOSQUITTO) =================
const char* MQTT_BROKER = "IP_MÁQUINA_AWS"; 
const int MQTT_PORT = 1883;
const char* TOPICO_CONFIG = "dispenser/config";
const char* TOPICO_STATUS = "dispenser/status";

// ================= PINOS (SUA MONTAGEM FÍSICA) =================
const int PINO_SERVO = 32;
const int PINO_BUZZER = 13;
const int PINO_TRIG = 25;
const int PINO_ECHO = 33;
const int SDA_PIN = 21;
const int SCL_PIN = 22;

// ================= CONFIGURAÇÕES =================
float ALTURA_RESERVATORIO_CM = 30.0; // Distância total do sensor ao fundo
float DIST_IDEAL = 5.0;            // Pote cheio (cm)

String horarioAlimentacao = "08:00";

Servo servo;
WiFiClient espClient;
PubSubClient client(espClient);
LiquidCrystal_I2C lcd(0x27, 16, 2);

void setup() {
  Serial.begin(115200);
  Wire.begin(SDA_PIN, SCL_PIN);

  lcd.init();
  lcd.backlight();
  lcd.print("Iniciando EC2...");

  pinMode(PINO_BUZZER, OUTPUT);
  pinMode(PINO_TRIG, OUTPUT);
  pinMode(PINO_ECHO, INPUT);

  servo.attach(PINO_SERVO);
  servo.write(0);

  conectarWiFi();

  // Configuração de Tempo (Brasília)
  configTime(-10800, 0, "pool.ntp.org");

  client.setServer(MQTT_BROKER, MQTT_PORT);
  client.setCallback(receberMensagemMQTT);

  conectarMQTT();
}

void loop() {
  if (!client.connected()) {
    conectarMQTT();
  }
  client.loop();

  verificarHorario();

  // Atualiza visor e status a cada 10 segundos
  static unsigned long ultimaPublicacao = 0;
  if (millis() - ultimaPublicacao > 10000) {
    float nivel = medirNivelRacao();
    atualizarVisor(nivel);
    publicarStatus("online");
    ultimaPublicacao = millis();
  }
}

// ================= LÓGICA DE ALIMENTAÇÃO =================

void liberarRacao() {
  lcd.clear();
  lcd.print("Calculando...");

  float distanciaAtual = lerDistanciaFisica();
  
  // LÓGICA PROPORCIONAL: 
  // Se a distância for 10cm (ideal), abre 1.5s.
  // Se estiver mais vazio (ex: 20cm), ele abre por mais tempo.
  int tempoCalculado = (distanciaAtual / DIST_IDEAL) * 1000;
  tempoCalculado = constrain(tempoCalculado, 1500, 5000); 

  Serial.println("Liberando racao por " + String(tempoCalculado) + "ms");
  lcd.setCursor(0, 1);
  lcd.print("Aberto: " + String(tempoCalculado/1000) + "s");

  digitalWrite(PINO_BUZZER, HIGH);
  servo.write(180);
  delay(tempoCalculado);
  servo.write(0);
  digitalWrite(PINO_BUZZER, LOW);

  delay(1000);
  publicarStatus("alimentacao_concluida");
}

// ================= SENSORES E DISPLAY =================

float lerDistanciaFisica() {
  digitalWrite(PINO_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PINO_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(PINO_TRIG, LOW);
  long duracao = pulseIn(PINO_ECHO, HIGH, 30000);
  float dist = duracao * 0.034 / 2;
  return (dist == 0) ? ALTURA_RESERVATORIO_CM : dist;
}

float medirNivelRacao() {
  float distancia = lerDistanciaFisica();
  float percentual = ((ALTURA_RESERVATORIO_CM - distancia) / ALTURA_RESERVATORIO_CM) * 100.0;
  return constrain(percentual, 0, 100);
}

void atualizarVisor(float nivel) {
  struct tm timeinfo;
  if(!getLocalTime(&timeinfo)) return;
  
  lcd.clear();
  lcd.setCursor(0,0);
  lcd.print("Hora: ");
  if(timeinfo.tm_hour < 10) lcd.print("0"); lcd.print(timeinfo.tm_hour);
  lcd.print(":");
  if(timeinfo.tm_min < 10) lcd.print("0"); lcd.print(timeinfo.tm_min);
  
  lcd.setCursor(0,1);
  lcd.print("Nivel: ");
  lcd.print((int)nivel); lcd.print("%");
}

// ================= CONEXÕES =================

void conectarWiFi() {
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nWiFi Conectado!");
}

void conectarMQTT() {
  while (!client.connected()) {
    Serial.print("Tentando conectar ao Mosquitto...");
    if (client.connect("ESP32_Dispenser_Gato")) {
      Serial.println("Conectado!");
      client.subscribe(TOPICO_CONFIG);
    } else {
      Serial.print("falhou, rc=");
      Serial.print(client.state());
      delay(5000);
    }
  }
}

void receberMensagemMQTT(char* topic, byte* payload, unsigned int length) {
  StaticJsonDocument<256> doc;
  deserializeJson(doc, payload, length);

  if (doc.containsKey("comando")) {
    String cmd = doc["comando"].as<String>();
    if (cmd == "ALIMENTAR_AGORA") liberarRacao();
    if (cmd == "MEDIR_NIVEL") publicarStatus("medicao_manual");
  }
  
  if (doc.containsKey("novo_horario")) {
    horarioAlimentacao = doc["novo_horario"].as<String>();
    Serial.println("Novo horario agendado: " + horarioAlimentacao);
  }
}

void verificarHorario() {
  struct tm timeinfo;
  if (!getLocalTime(&timeinfo)) return;
  char horaAtual[6];
  strftime(horaAtual, sizeof(horaAtual), "%H:%M", &timeinfo);

  if (String(horaAtual) == horarioAlimentacao) {
    liberarRacao();
    delay(60000); // Bloqueia por 1 minuto para nao repetir
  }
}

void publicarStatus(String evento) {
  StaticJsonDocument<256> doc;
  doc["evento"] = evento;
  doc["nivel"] = (int)medirNivelRacao();
  doc["proximo_horario"] = horarioAlimentacao;
  
  char buffer[256];
  serializeJson(doc, buffer);
  client.publish(TOPICO_STATUS, buffer);
}