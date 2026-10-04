package com.projectorguy.app;

import android.content.Context;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.crypto.Cipher;

/** Device-local ADB only. No remote hosts or web-supplied commands. */
final class LocalAdb implements AutoCloseable {
 private final Socket socket=new Socket(); private final InputStream in; private final OutputStream out; private final RSAPrivateCrtKey key; private int nextStream=1;
 private static int tag(String s){return ByteBuffer.wrap(s.getBytes(StandardCharsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).getInt();}
 private static final int CNXN=tag("CNXN"),AUTH=tag("AUTH"),OPEN=tag("OPEN"),OKAY=tag("OKAY"),WRTE=tag("WRTE"),CLSE=tag("CLSE");
 LocalAdb(Context context) throws Exception {
  File file=new File(context.getNoBackupFilesDir(),"optimisation-adb-key.pk8");
  if(file.exists()){byte[] b=new byte[(int)file.length()];try(DataInputStream f=new DataInputStream(new FileInputStream(file))){f.readFully(b);}key=(RSAPrivateCrtKey)KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(b));}
  else{KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);key=(RSAPrivateCrtKey)g.generateKeyPair().getPrivate();try(FileOutputStream f=new FileOutputStream(file)){f.write(key.getEncoded());}}
  try { socket.connect(new InetSocketAddress("127.0.0.1",5555),3000);socket.setSoTimeout(60000);in=socket.getInputStream();out=socket.getOutputStream();
   send(CNXN,0x01000000,4096,"host::\0".getBytes(StandardCharsets.UTF_8));boolean signed=false,sentKey=false;
   for(int i=0;i<5;i++){Packet p=read();if(p.cmd==CNXN)return;if(p.cmd!=AUTH || p.a!=1 || p.data.length!=20)throw new IOException("Unexpected ADB authorisation response");
    if(!signed){byte[] prefix={0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};byte[] digest=new byte[35];System.arraycopy(prefix,0,digest,0,15);System.arraycopy(p.data,0,digest,15,20);Cipher c=Cipher.getInstance("RSA/ECB/PKCS1Padding");c.init(Cipher.ENCRYPT_MODE,key);send(AUTH,2,0,c.doFinal(digest));signed=true;}
    else if(!sentKey){send(AUTH,3,0,publicKey());sentKey=true;}else throw new IOException("Approve Projector Guy in the projector's USB debugging prompt, then retry.");
   }throw new IOException("ADB authorisation did not complete");
  }catch(Exception e){socket.close();throw e;}
 }
 private byte[] publicKey(){BigInteger n=key.getModulus(),mod=BigInteger.ONE.shiftLeft(32);ByteBuffer b=ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);b.putInt(64);b.putInt(n.mod(mod).modInverse(mod).negate().mod(mod).intValue());b.put(little(n));b.put(little(BigInteger.ONE.shiftLeft(4096).mod(n)));b.putInt(key.getPublicExponent().intValue());return (Base64.encodeToString(b.array(),Base64.NO_WRAP)+" projectorguy@projector\0").getBytes(StandardCharsets.US_ASCII);}
 private static byte[] little(BigInteger n){byte[] s=n.toByteArray(),r=new byte[256];for(int i=0;i<256&&i<s.length;i++)r[i]=s[s.length-1-i];return r;}
 String shell(String command) throws Exception {
  int local=nextStream++; send(OPEN,local,0,("shell:"+command+"\0").getBytes(StandardCharsets.UTF_8));ByteArrayOutputStream result=new ByteArrayOutputStream();int remote=0;long deadline=System.nanoTime()+60000000000L;
  while(System.nanoTime()<deadline){Packet p=read();if(p.b!=local)continue;if(p.cmd==OKAY){remote=p.a;continue;}if(p.cmd==WRTE){remote=p.a;if(result.size()+p.data.length>262144)throw new IOException("ADB output too large");result.write(p.data);send(OKAY,local,remote,new byte[0]);}else if(p.cmd==CLSE){send(CLSE,local,p.a,new byte[0]);return result.toString("UTF-8");}else throw new IOException("Unexpected ADB packet");}throw new IOException("ADB command timed out");
 }
 private void send(int cmd,int a,int b,byte[] data)throws IOException{int sum=0;for(byte v:data)sum+=v&255;ByteBuffer h=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);h.putInt(cmd).putInt(a).putInt(b).putInt(data.length).putInt(sum).putInt(cmd^0xffffffff);out.write(h.array());out.write(data);out.flush();}
 private Packet read()throws IOException{byte[] h=new byte[24];new DataInputStream(in).readFully(h);ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);Packet p=new Packet();p.cmd=b.getInt();p.a=b.getInt();p.b=b.getInt();int len=b.getInt(),sum=b.getInt(),magic=b.getInt();if(len<0||len>1048576||magic!=(p.cmd^0xffffffff))throw new IOException("Invalid ADB packet");p.data=new byte[len];new DataInputStream(in).readFully(p.data);int actual=0;for(byte v:p.data)actual+=v&255;if(sum!=0&&sum!=actual)throw new IOException("Invalid ADB checksum");return p;}
 private static class Packet{int cmd,a,b;byte[] data;}
 public void close()throws IOException{socket.close();}
}
