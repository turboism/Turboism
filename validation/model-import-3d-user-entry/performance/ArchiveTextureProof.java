package dev.turboism.diagnostic;

import com.live2d.serialize.archiver.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.io.*;

/** Streaming comparison of every native archive payload except deliberately changed main.xml. */
public final class ArchiveTextureProof {
    static SortedSet<String> paths(ArchiveReader reader)throws Exception {
        var field=ArchiveReader.class.getDeclaredField("fileInfoList");field.setAccessible(true);
        var paths=new TreeSet<String>();
        for(var info:(List<?>)field.get(reader)) {
            String path=(String)info.getClass().getMethod("a").invoke(info);
            if(!paths.add(path))throw new AssertionError("duplicate entry "+path);
        }
        if(!paths.contains("main.xml"))throw new AssertionError("unreviewed native entry path");
        return paths;
    }
    static String digest(ArchiveReader reader,String path)throws Exception {
        var hash=MessageDigest.getInstance("SHA-256");long[] count={0};
        reader.readFileByFilePath(path,input->{
            byte[] buffer=new byte[65536];
            try { for(int n;(n=input.read(buffer))!=-1;) { hash.update(buffer,0,n);count[0]+=n; } }
            catch(IOException failure) { throw new UncheckedIOException(failure); }
            return kotlin.Unit.INSTANCE;
        });
        return count[0]+":"+HexFormat.of().formatHex(hash.digest());
    }
    public static void main(String[] args)throws Exception {
        var source=new ArchiveReader(Path.of(args[0]).toFile());var compact=new ArchiveReader(Path.of(args[1]).toFile());
        try {
            var entries=paths(source);
            if(!entries.equals(paths(compact)))throw new AssertionError("archive entry inventory changed");
            int images=0;
            for(String path:entries) {
                String a=digest(source,path),b=digest(compact,path);
                if(!path.equals("main.xml")) {
                    if(!a.equals(b))throw new AssertionError("payload changed "+path);
                    if(path.endsWith(".png"))images++;
                } else System.out.println("MAIN_XML_SOURCE "+a+" COMPACT "+b);
            }
            if(images==0)throw new AssertionError("no texture payloads");
            System.out.println("PASS_ALL_NATIVE_NON_XML_PAYLOADS_EXACT entries="+entries.size()+" pngs="+images);
        } finally { source.close();compact.close(); }
    }
}
