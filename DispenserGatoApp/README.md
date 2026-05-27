# DispenserGatoApp - versão MQTT Mosquitto/EC2

Aplicativo Android para controlar um dispenser automático de ração usando:

App Android -> Broker Mosquitto na EC2 -> ESP32 -> servo/sensor/buzzer

## Configuração no app

Preencha:
- Broker / EC2: IP público ou DNS da sua instância EC2
- Porta: 1883
- Usuário/Senha: deixe vazio se seu Mosquitto estiver sem autenticação
- Publicar comandos em: dispenser/config
- Ler status de: dispenser/status

Depois clique em **Salvar configurações MQTT** e **Conectar ao Mosquitto**.

## Comandos enviados

Alimentar agora publica em `dispenser/config`:

```json
{"comando":"ALIMENTAR_AGORA"}
```

Salvar horário publica:

```json
{"novo_horario":"08:00","tempo_abertura":2000}
```

Pedir leitura publica:

```json
{"comando":"MEDIR_NIVEL"}
```

## Status esperado do ESP32

O ESP32 deve publicar em `dispenser/status` algo como:

```json
{"evento":"online","nivel":75,"proximo_horario":"08:00","tempo_abertura":2000,"wifi":"conectado"}
```

## EC2

No Security Group da EC2, libere a porta TCP 1883 para teste.
