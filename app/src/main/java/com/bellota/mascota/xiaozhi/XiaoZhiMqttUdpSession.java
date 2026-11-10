package com.bellota.mascota.xiaozhi;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.util.Log;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.json.JSONObject;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** XiaoZhi MQTT control + UDP/AES-128-CTR audio transport (protocol v3). */
public final class XiaoZhiMqttUdpSession {
    private static final String TAG="BellotaXiaoZhiMQTT";
    private static final int RATE=16000, FRAME_MS=60, SAMPLES=RATE*FRAME_MS/1000, HEADER=16;
    public interface Listener {
        void onStatus(String status);
        void onTranscript(String text);
        void onSpeaking(boolean speaking);
        void onError(String message);
    }
    private final XiaoZhiConnection identity;
    private final Listener listener;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService capture=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
    private volatile ScheduledFuture<?> helloTimeout;
    private final AtomicBoolean active=new AtomicBoolean(false), recording=new AtomicBoolean(false);
    private volatile MqttClient mqtt;
    private volatile DatagramSocket udp;
    private volatile InetAddress udpHost;
    private volatile int udpPort, downRate=24000;
    private volatile String publishTopic, sessionId;
    private volatile byte[] aesKey, baseNonce;
    private volatile boolean speaking;
    private volatile long txSequence, rxSequence;
    private volatile int timestamp;
    private AudioRecord recorder;
    private AudioTrack player;
    private OpusEncoder encoder;
    private OpusDecoder decoder;

    public XiaoZhiMqttUdpSession(XiaoZhiConnection c, Listener l){identity=c;listener=l;}
    public boolean isRunning(){return active.get();}

    public void connect(){
        if(active.get()) return;
        JSONObject cfg=identity.getMqttConfig();
        if(cfg==null){fail("XiaoZhi no entregó configuración MQTT.");return;}
        String endpoint=cfg.optString("endpoint","").trim();
        String clientId=cfg.optString("client_id",identity.getClientId());
        String username=cfg.optString("username","");
        String password=cfg.optString("password","");
        publishTopic=cfg.optString("publish_topic","");
        String subscribeTopic=cfg.optString("subscribe_topic","");
        if(endpoint.isEmpty()||clientId.isEmpty()||username.isEmpty()||password.isEmpty()
                ||publishTopic.isEmpty()||subscribeTopic.isEmpty()){
            fail("La configuración MQTT está incompleta. Se requieren endpoint, credenciales y tópicos de publicación y suscripción; no se supondrá ningún tópico.");
            return;
        }
        if(!endpoint.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) endpoint="ssl://"+endpoint;
        final String broker=endpoint, cid=clientId, user=username, pass=password, sub=subscribeTopic;
        io.execute(()->{
            try{
                report("Conectando al canal de control MQTT…");
                MqttClient client=new MqttClient(broker,cid,new MemoryPersistence());
                MqttConnectOptions options=new MqttConnectOptions();
                options.setUserName(user); options.setPassword(pass.toCharArray());
                options.setCleanSession(true); options.setAutomaticReconnect(true);
                options.setKeepAliveInterval(cfg.optInt("keepalive",240));
                client.setCallback(new MqttCallback(){
                    @Override public void connectionLost(Throwable cause){ if(active.get()) fail("Se perdió la conexión MQTT con XiaoZhi: "+(cause==null?"desconexión":safe(cause))); }
                    @Override public void messageArrived(String topic,MqttMessage message){handleControl(new String(message.getPayload(),java.nio.charset.StandardCharsets.UTF_8));}
                    @Override public void deliveryComplete(IMqttDeliveryToken token){}
                });
                client.connect(options);
                mqtt=client; active.set(true);
                client.subscribe(sub,1);
                JSONObject hello=new JSONObject().put("type","hello").put("version",3).put("transport","udp")
                        .put("features",new JSONObject().put("mcp",true))
                        .put("audio_params",new JSONObject().put("format","opus").put("sample_rate",RATE)
                                .put("channels",1).put("frame_duration",FRAME_MS));
                publish(hello);
                helloTimeout = watchdog.schedule(() -> {
                    if (active.get() && udp == null) {
                        fail("MQTT conectó, pero XiaoZhi no respondió al hello UDP en 12 segundos.");
                    }
                }, 12, TimeUnit.SECONDS);
                report("MQTT conectado. Esperando negociación del canal UDP…");
            }catch(Exception e){fail("No se pudo conectar o negociar MQTT: "+safe(e));}
        });
    }

    private void handleControl(String raw){
        try{
            JSONObject msg=new JSONObject(raw); String type=msg.optString("type","");
            if("hello".equals(type)){
                ScheduledFuture<?> timeout = helloTimeout;
                if (timeout != null) timeout.cancel(false);
                if(!"udp".equals(msg.optString("transport",""))){fail("El servidor no confirmó transporte UDP.");return;}
                String sid=msg.optString("session_id","");
                JSONObject udpCfg=msg.optJSONObject("udp");
                if(sid.isEmpty()||udpCfg==null){fail("La respuesta hello no contiene session_id o parámetros UDP.");return;}
                byte[] key=hex(udpCfg.optString("key","")), nonce=hex(udpCfg.optString("nonce",""));
                if(key.length!=16||nonce.length!=16){fail("La clave AES o el nonce UDP no tienen 128 bits.");return;}
                int port=udpCfg.optInt("port",0); String host=udpCfg.optString("server","");
                if(port<1||port>65535||host.isEmpty()){fail("El servidor UDP o el puerto no son válidos.");return;}
                JSONObject ap=msg.optJSONObject("audio_params");
                String format=ap==null?"opus":ap.optString("format","opus");
                int rate=ap==null?24000:ap.optInt("sample_rate",24000);
                int channels=ap==null?1:ap.optInt("channels",1);
                int duration=ap==null?60:ap.optInt("frame_duration",60);
                if(!"opus".equalsIgnoreCase(format)||channels!=1||duration!=60
                        ||(rate!=16000&&rate!=24000&&rate!=48000)){
                    fail("Parámetros de audio negociados no compatibles: "+format+", "+rate+" Hz, "+channels+" canales, "+duration+" ms.");return;
                }
                DatagramSocket socket=new DatagramSocket();
                socket.connect(InetAddress.getByName(host),port);
                rxSequence=0; txSequence=0; timestamp=0; sessionId=sid; aesKey=key; baseNonce=nonce; udpHost=socket.getInetAddress();udpPort=port;udp=socket;
                downRate=rate; decoder=new OpusDecoder(rate,1); encoder=new OpusEncoder(RATE,1,OpusApplication.OPUS_APPLICATION_VOIP);
                createPlayer(); report("Canal UDP negociado. Audio Opus "+rate+" Hz con AES-128-CTR.");
                Thread receiver=new Thread(this::receiveLoop,"Bellota-UDP-RX");receiver.setDaemon(true);receiver.start();
                startListening();
            }else if("stt".equals(type)){
                String text=msg.optString("text","");if(!text.isEmpty()&&listener!=null)listener.onTranscript(text);
            }else if("tts".equals(type)){
                String state=msg.optString("state","");
                if("start".equals(state)){speaking=true;stopCapture();if(listener!=null)listener.onSpeaking(true);}
                else if("stop".equals(state)){speaking=false;if(listener!=null)listener.onSpeaking(false);startListening();}
            }else if("llm".equals(type)){
                report("XiaoZhi: "+msg.optString("emotion","está pensando…"));
            }else if("goodbye".equals(type)){
                fail("XiaoZhi cerró la sesión de audio UDP.");
            }
        }catch(Exception e){fail("Respuesta MQTT/UDP inválida: "+safe(e));}
    }

    private void startListening(){
        if(!active.get()||speaking||udp==null||recording.get())return;
        try{
            publish(new JSONObject().put("session_id",sessionId).put("type","listen").put("state","start").put("mode","auto"));
            int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,RATE,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,Math.max(min,SAMPLES*8));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Micrófono no inicializado");
            recorder.startRecording(); AudioRecord source=recorder; recording.set(true);
            capture.execute(()->{
                short[] read=new short[SAMPLES], frame=new short[SAMPLES];byte[] opus=new byte[4000];int filled=0;
                while(recording.get()&&active.get()&&!speaking){
                    int n=source.read(read,0,read.length);if(n<=0)continue;
                    int off=0;while(off<n&&recording.get()&&!speaking){
                        int count=Math.min(SAMPLES-filled,n-off);System.arraycopy(read,off,frame,filled,count);filled+=count;off+=count;
                        if(filled==SAMPLES)try{
                            int len=encoder.encode(frame,0,SAMPLES,opus,0,opus.length);
                            if(len>0)sendAudio(Arrays.copyOf(opus,len));
                            timestamp+=SAMPLES;filled=0;
                        }catch(Exception e){fail("Error codificando Opus.");return;}
                    }
                }
            });
        }catch(Exception e){fail("No se pudo iniciar la captura de voz: "+safe(e));}
    }

    private void sendAudio(byte[] payload)throws Exception{
        if(payload.length>65535)throw new IllegalArgumentException("Paquete Opus demasiado grande");
        byte[] header=baseNonce.clone();
        // El nonce OTA aporta el bloque base de 128 bits, pero los campos de
        // cabecera del datagrama deben respetar el protocolo UDP: tipo 0x01,
        // flags reservados en cero, longitud, timestamp y secuencia.
        header[0]=0x01;
        header[1]=0x00;
        ByteBuffer b=ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        b.putShort(2,(short)payload.length);b.putInt(8,timestamp);b.putInt(12,(int)++txSequence);
        byte[] encrypted=crypt(payload,header);
        byte[] packet=new byte[HEADER+encrypted.length];System.arraycopy(header,0,packet,0,HEADER);
        System.arraycopy(encrypted,0,packet,HEADER,encrypted.length);
        DatagramSocket s=udp;if(s!=null&&!s.isClosed())s.send(new DatagramPacket(packet,packet.length,udpHost,udpPort));
    }

    private void receiveLoop(){
        byte[] buffer=new byte[65507];
        while(active.get()&&udp!=null&&!udp.isClosed()){
            try{
                DatagramPacket p=new DatagramPacket(buffer,buffer.length);udp.receive(p);
                if(p.getLength()<HEADER||buffer[0]!=1)continue;
                ByteBuffer h=ByteBuffer.wrap(buffer,0,HEADER).order(ByteOrder.BIG_ENDIAN);
                int len=h.getShort(2)&65535;long seq=h.getInt(12)&0xffffffffL;
                if(len!=p.getLength()-HEADER||seq<=rxSequence)continue;
                rxSequence=seq;
                byte[] iv=Arrays.copyOfRange(buffer,0,HEADER), cipher=Arrays.copyOfRange(buffer,HEADER,p.getLength());
                byte[] plain=crypt(cipher,iv);short[] pcm=new short[5760];
                int samples=decoder.decode(plain,0,plain.length,pcm,0,pcm.length,false);
                if(samples>0&&player!=null){if(player.getPlayState()!=AudioTrack.PLAYSTATE_PLAYING)player.play();player.write(pcm,0,samples);}
            }catch(Exception e){if(active.get())Log.w(TAG,"Paquete UDP descartado",e);}
        }
    }

    private byte[] crypt(byte[] input,byte[] iv)throws Exception{
        Cipher c=Cipher.getInstance("AES/CTR/NoPadding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aesKey,"AES"),new IvParameterSpec(iv));
        return c.doFinal(input);
    }
    private static byte[] hex(String value){
        if(value==null||value.length()!=32)return new byte[0];
        try{byte[] out=new byte[16];for(int i=0;i<16;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return out;}
        catch(Exception e){return new byte[0];}
    }
    private void publish(JSONObject json)throws Exception{
        MqttClient c=mqtt;if(c==null||!c.isConnected())throw new IllegalStateException("MQTT desconectado");
        MqttMessage m=new MqttMessage(json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));m.setQos(1);c.publish(publishTopic,m);
    }
    private void stopCapture(){
        recording.set(false);AudioRecord r=recorder;recorder=null;
        if(r!=null){try{r.stop();}catch(Exception ignored){}r.release();}
    }
    private synchronized void createPlayer(){
        releasePlayer();int min=AudioTrack.getMinBufferSize(downRate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        player=new AudioTrack(AudioManager.STREAM_MUSIC,downRate,AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,Math.max(min,downRate/2),AudioTrack.MODE_STREAM);
    }
    private synchronized void releasePlayer(){if(player!=null){try{player.pause();player.flush();player.stop();}catch(Exception ignored){}player.release();player=null;}}
    private void closeUdp(){stopCapture();DatagramSocket s=udp;udp=null;if(s!=null)s.close();releasePlayer();}
    public void disconnect(){
        active.set(false);speaking=false;
        ScheduledFuture<?> timeout=helloTimeout;if(timeout!=null)timeout.cancel(false);
        stopCapture();
        try{if(sessionId!=null&&mqtt!=null&&mqtt.isConnected())publish(new JSONObject().put("session_id",sessionId).put("type","goodbye"));}catch(Exception ignored){}
        closeUdp();MqttClient c=mqtt;mqtt=null;if(c!=null)try{c.disconnect();c.close();}catch(Exception ignored){}
        io.shutdownNow();capture.shutdownNow();watchdog.shutdownNow();
    }
    private void fail(String message){Log.e(TAG,message);active.set(false);ScheduledFuture<?> timeout=helloTimeout;if(timeout!=null)timeout.cancel(false);closeUdp();MqttClient c=mqtt;mqtt=null;if(c!=null)try{c.disconnect();c.close();}catch(Exception ignored){}if(listener!=null)listener.onError(message);}
    private void report(String s){if(listener!=null)listener.onStatus(s);}
    private static String safe(Exception e){String m=e.getMessage();return m==null?"error de conexión":m;}
}
