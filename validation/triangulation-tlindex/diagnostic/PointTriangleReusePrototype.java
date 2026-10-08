import java.util.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Owned loader experiment only; no production installation or host editor. */
public final class PointTriangleReusePrototype {
    static final String VECTOR="com/live2d/graphics3d/type/GVector2";
    static final String POINT="com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangleD";
    static final String METHOD="(L"+VECTOR+";[F[IIZ)L"+POINT+";";
    static AbstractInsnNode real(AbstractInsnNode n) {while(n!=null && n.getOpcode()<0)n=n.getNext();return n;}
    static byte[] patch(byte[] bytes, boolean point) {
        ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,ClassReader.SKIP_FRAMES);
        if (!point) {
            c.fields.add(new FieldNode(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"ownedConstructorCount","J",null,null));
            for(MethodNode m:c.methods)if(m.name.equals("<init>") && m.desc.equals("(FF)V")) {
                for(AbstractInsnNode n:m.instructions.toArray())if(n.getOpcode()==Opcodes.RETURN) {
                    InsnList count=new InsnList();count.add(new FieldInsnNode(Opcodes.GETSTATIC,VECTOR,"ownedConstructorCount","J"));count.add(new InsnNode(Opcodes.LCONST_1));count.add(new InsnNode(Opcodes.LADD));count.add(new FieldInsnNode(Opcodes.PUTSTATIC,VECTOR,"ownedConstructorCount","J"));m.instructions.insertBefore(n,count);
                }
            }
        } else {
            MethodNode m=c.methods.stream().filter(x->x.name.equals("a") && x.desc.equals(METHOD)).findFirst().orElseThrow();
            int cache=m.maxLocals, x=cache+3, y=x+1; m.maxLocals=y+1;
            InsnList init=new InsnList();for(int i=0;i<3;i++){init.add(new InsnNode(Opcodes.ACONST_NULL));init.add(new VarInsnNode(Opcodes.ASTORE,cache+i));}m.instructions.insert(init);
            List<TypeInsnNode> allocations=new ArrayList<>();for(AbstractInsnNode n:m.instructions.toArray())if(n instanceof TypeInsnNode t && t.getOpcode()==Opcodes.NEW && t.desc.equals(VECTOR))allocations.add(t);
            if(allocations.size()!=9)throw new IllegalArgumentException("exact nine vector creation sites required");
            int site=0;
            for(TypeInsnNode n:allocations) {
                AbstractInsnNode dup=real(n.getNext());if(dup.getOpcode()!=Opcodes.DUP)throw new IllegalArgumentException("creation shape");
                AbstractInsnNode ctor=dup.getNext();while(!(ctor instanceof MethodInsnNode call && call.getOpcode()==Opcodes.INVOKESPECIAL && call.owner.equals(VECTOR) && call.name.equals("<init>") && call.desc.equals("(FF)V"))) {if(ctor==null)throw new IllegalArgumentException("constructor missing");ctor=ctor.getNext();}
                int local=cache+(site++%3);LabelNode existing=new LabelNode(),done=new LabelNode();InsnList replacement=new InsnList();
                replacement.add(new VarInsnNode(Opcodes.FSTORE,y));replacement.add(new VarInsnNode(Opcodes.FSTORE,x));
                replacement.add(new VarInsnNode(Opcodes.ALOAD,local));replacement.add(new JumpInsnNode(Opcodes.IFNONNULL,existing));
                replacement.add(new TypeInsnNode(Opcodes.NEW,VECTOR));replacement.add(new InsnNode(Opcodes.DUP));replacement.add(new VarInsnNode(Opcodes.FLOAD,x));replacement.add(new VarInsnNode(Opcodes.FLOAD,y));replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,VECTOR,"<init>","(FF)V",false));replacement.add(new VarInsnNode(Opcodes.ASTORE,local));replacement.add(new JumpInsnNode(Opcodes.GOTO,done));
                replacement.add(existing);replacement.add(new VarInsnNode(Opcodes.ALOAD,local));replacement.add(new VarInsnNode(Opcodes.FLOAD,x));replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,VECTOR,"setX","(F)V",false));replacement.add(new VarInsnNode(Opcodes.ALOAD,local));replacement.add(new VarInsnNode(Opcodes.FLOAD,y));replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,VECTOR,"setY","(F)V",false));
                replacement.add(done);replacement.add(new VarInsnNode(Opcodes.ALOAD,local));
                m.instructions.insertBefore(ctor,replacement);m.instructions.remove(ctor);m.instructions.remove(dup);m.instructions.remove(n);
            }
        }
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS) {
            @Override protected String getCommonSuperClass(String a,String b){return a.equals(b)?a:"java/lang/Object";}
        };c.accept(writer);return writer.toByteArray();
    }
    static final class Loader extends URLClassLoader {
        final boolean candidate;
        Loader(URL[] urls,boolean candidate){super(urls,ClassLoader.getPlatformClassLoader());this.candidate=candidate;}
        @Override protected Class<?> findClass(String name)throws ClassNotFoundException {
            if(name.equals(VECTOR.replace('/','.')) || candidate && name.equals((POINT+"$a").replace('/','.'))) {
                URL resource=getResource(name.replace('.','/')+".class");if(resource==null)throw new ClassNotFoundException(name);
                try {JarURLConnection connection=(JarURLConnection)resource.openConnection();byte[] original;try(var in=connection.getInputStream()){original=in.readAllBytes();}
                    byte[] bytes=patch(original,!name.equals(VECTOR.replace('/','.')));
                    java.security.CodeSource source=new java.security.CodeSource(connection.getJarFileURL(),connection.getCertificates());
                    return defineClass(name,bytes,0,bytes.length,source);
                }catch(java.io.IOException e){throw new ClassNotFoundException(name,e);}
            }
            return super.findClass(name);
        }
    }
    static final class Arm implements AutoCloseable {
        final Loader loader;final Class<?> vector;final Constructor<?> constructor;final Method method;final Object companion;final Field count;
        Arm(URL[] urls,boolean candidate)throws Exception {
            loader=new Loader(urls,candidate);vector=loader.loadClass(VECTOR.replace('/','.'));constructor=vector.getConstructor(float.class,float.class);count=vector.getField("ownedConstructorCount");Class<?> outer=loader.loadClass(POINT.replace('/','.'));Class<?> type=loader.loadClass((POINT+"$a").replace('/','.'));Field field=Arrays.stream(outer.getDeclaredFields()).filter(f->Modifier.isStatic(f.getModifiers()) && f.getType()==type).findFirst().orElseThrow();field.setAccessible(true);companion=field.get(null);method=type.getMethod("a",vector,float[].class,int[].class,int.class,boolean.class);
        }
        String invoke(Float px,Float py,float[] coords,int[] indices,int stride,boolean nullable)throws Exception {
            Object p=px==null?null:constructor.newInstance(px,py);float[] before=coords==null?null:coords.clone();int[] beforeIndices=indices==null?null:indices.clone();
            try {Object result=method.invoke(companion,p,coords,indices,stride,nullable);if(!Arrays.equals(before,coords)||!Arrays.equals(beforeIndices,indices))throw new AssertionError("input mutated");if(p!=null && (Float.floatToRawIntBits((float)vector.getMethod("getX").invoke(p))!=Float.floatToRawIntBits(px) || Float.floatToRawIntBits((float)vector.getMethod("getY").invoke(p))!=Float.floatToRawIntBits(py)))throw new AssertionError("point mutated");return snapshot(result);}catch(InvocationTargetException e){Throwable t=e.getCause();return "throw:"+t.getClass().getName()+":"+t.getMessage();}
        }
        long count()throws Exception{return count.getLong(null);}
        @Override public void close()throws java.io.IOException{loader.close();}
    }
    static String snapshot(Object value)throws Exception {
        if(value==null)return "null";List<String> fields=new ArrayList<>();for(Field f:value.getClass().getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())) {f.setAccessible(true);Object v=f.get(value);String s=v instanceof Double d?Long.toHexString(Double.doubleToRawLongBits(d)):String.valueOf(v);fields.add(f.getName()+":"+s);}Collections.sort(fields);return fields.toString();
    }
    static int checks;
    static void compare(Arm a,Arm b,Float x,Float y,float[] coords,int[] indices,int stride,boolean nullable)throws Exception {
        String left=a.invoke(x,y,coords==null?null:coords.clone(),indices==null?null:indices.clone(),stride,nullable),right=b.invoke(x,y,coords==null?null:coords.clone(),indices==null?null:indices.clone(),stride,nullable);if(!left.equals(right))throw new AssertionError("mismatch "+left+" != "+right);checks++;
    }
    public static void main(String[] args)throws Exception {
        for(String jar:args){Path file=Path.of(jar);URL[] urls;try(var files=Files.list(file.getParent())){urls=files.filter(p->p.toString().endsWith(".jar")).map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);}
            int start=checks;try(Arm a=new Arm(urls,false);Arm b=new Arm(urls,true)) {
                Random random=new Random(102103);float[] edge={0f,-0f,Float.MIN_VALUE,-Float.MIN_VALUE,Float.MAX_VALUE,-Float.MAX_VALUE,Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,1f,-1f};
                for(int i=0;i<1200;i++) {float[] coords=new float[18];for(int j=0;j<coords.length;j++)coords[j]=i<200?edge[random.nextInt(edge.length)]:(random.nextFloat()-.5f)*100;int[] indices=new int[18];for(int j=0;j<indices.length;j++)indices[j]=random.nextInt(9);float x=i<200?edge[random.nextInt(edge.length)]:(random.nextFloat()-.5f)*100,y=i<200?edge[random.nextInt(edge.length)]:(random.nextFloat()-.5f)*100;compare(a,b,x,y,coords,indices,2,true);compare(a,b,x,y,coords,indices,2,false);}
                float[] triangle={0,0,1,0,0,1};int[] valid={0,1,2};
                for(int[] idx:new int[][]{valid,{}, {-1,-1,-1}, {-1,0,1}, {0,1}, {0,1,2,0}, {0,1,99}})for(int stride:new int[]{-1,0,1,2,3,Integer.MAX_VALUE})for(boolean nullable:new boolean[]{true,false})compare(a,b,.2f,.2f,triangle,idx,stride,nullable);
                compare(a,b,null,null,triangle,valid,2,true);compare(a,b,0f,0f,null,valid,2,true);compare(a,b,0f,0f,triangle,null,2,true);
                // Many misses followed by a hit exercise sequential reuse and detached return data.
                float[] coords=new float[606];int[] indices=new int[303];for(int i=0;i<101;i++){int o=i*6;float x=i==100?0:100+i;coords[o]=x;coords[o+1]=0;coords[o+2]=x+1;coords[o+3]=0;coords[o+4]=x;coords[o+5]=1;for(int j=0;j<3;j++)indices[i*3+j]=i*3+j;}
                long ca=a.count(),cb=b.count();compare(a,b,.2f,.2f,coords,indices,2,true);long naive=a.count()-ca,owned=b.count()-cb;
                if(naive-owned!=300)throw new AssertionError("expected 300 fewer three-corner allocations, got "+naive+"/"+owned);
                compare(a,b,10000f,10000f,coords,indices,2,false);
                System.out.println("POINT_TRIANGLE_OWNED_REUSE_PASS archive="+file+" cases="+(checks-start)+" constructorDelta="+naive+"/"+owned+" productionChanged=false");
            }
        }
        System.out.println("POINT_TRIANGLE_OWNED_REUSE_FINISHED checks="+checks);
    }
}
