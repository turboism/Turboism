/* Thin JNI bridge. Registry tokens are never native addresses. */
#include "windows_files.h"
#include <jni.h>
_Static_assert(sizeof(jint)==4&&sizeof(jlong)==8&&sizeof(void *)==8&&sizeof(WCHAR)==2&&sizeof(jchar)==2,"Windows x64 JNI ABI");
#define SLOTS 256
#define ROOT 1
#define FILE_TOKEN 2
typedef struct { uint64_t generation; int kind; void *value; unsigned parent; unsigned children; } slot;
static slot slots[SLOTS];
static uint64_t generation;
static SRWLOCK registry=SRWLOCK_INIT;
static void error(JNIEnv *env,const wf_error *e){
    char message[160];unsigned n=0;const char *s=e->operation;
    while(*s&&n<80)message[n++]=*s++;
    const char *label=" win32=0x";while(*label)message[n++]=*label++;
    const char *hex="0123456789abcdef";
    for(int k=7;k>=0;k--)message[n++]=hex[(e->win32>>(k*4))&15];
    label=" ntstatus=0x";while(*label)message[n++]=*label++;
    for(int k=7;k>=0;k--)message[n++]=hex[((DWORD)e->ntstatus>>(k*4))&15];
    message[n]=0;
    jclass type=(*env)->FindClass(env,"java/io/IOException");
    if(type)(*env)->ThrowNew(env,type,message);
}
static void invalid(JNIEnv *env,const char *op){wf_error e={ERROR_INVALID_HANDLE,0,op};error(env,&e);}
static slot *lookup(JNIEnv *env,jlong token,int kind){
    uint64_t bits=(uint64_t)token;unsigned id=(unsigned)(bits&65535);
    if(!id||id>SLOTS||!slots[id-1].value||slots[id-1].generation!=(bits>>16)
       ||slots[id-1].kind!=kind){invalid(env,"stale/wrong-kind native token");return 0;}
    return &slots[id-1];
}
static jlong insert(JNIEnv *env,void *value,int kind,unsigned parent){
    for(unsigned i=0;i<SLOTS;i++)if(!slots[i].value&&generation<UINT64_C(0x7fffffffffff)){
        slot *s=&slots[i];s->generation=++generation;s->kind=kind;s->value=value;s->parent=parent;s->children=0;
        if(kind==FILE_TOKEN)slots[parent].children++;
        return (jlong)((s->generation<<16)|(i+1));
    }
    wf_error e={ERROR_TOO_MANY_OPEN_FILES,0,"native registry capacity"};error(env,&e);return 0;
}
static WCHAR *string(JNIEnv *env,jstring input,size_t *length){
    if(!input){invalid(env,"null path");return 0;}
    jsize n=(*env)->GetStringLength(env,input);
    if(n<=0||n>30000){invalid(env,"path length");return 0;}
    WCHAR *p=HeapAlloc(GetProcessHeap(),0,((size_t)n+1)*sizeof(WCHAR));
    if(!p){wf_error e={ERROR_NOT_ENOUGH_MEMORY,0,"JNI path allocation"};error(env,&e);return 0;}
    (*env)->GetStringRegion(env,input,0,n,(jchar *)p);
    if((*env)->ExceptionCheck(env)){HeapFree(GetProcessHeap(),0,p);return 0;}
    p[n]=0;*length=(size_t)n;return p;
}
JNIEXPORT jlong JNICALL Java_dev_turboism_update_WindowsInstallationFiles_openRoot(JNIEnv *env,jclass type,jstring path){
    (void)type;size_t length;WCHAR *p=string(env,path,&length);if(!p)return 0;
    wf_root *root=0;wf_error e;int ok=wf_root_open(p,length,&root,&e);HeapFree(GetProcessHeap(),0,p);
    if(!ok){error(env,&e);return 0;}
    AcquireSRWLockExclusive(&registry);jlong token=insert(env,root,ROOT,0);ReleaseSRWLockExclusive(&registry);
    if(!token)wf_root_close(root);
    return token;
}
JNIEXPORT jbyteArray JNICALL Java_dev_turboism_update_WindowsInstallationFiles_identity(JNIEnv *env,jclass type,jlong token){
    (void)type;jbyteArray result=0;wf_error e;unsigned char bytes[24];
    AcquireSRWLockExclusive(&registry);slot *s=lookup(env,token,ROOT);
    if(s){if(!wf_root_identity(s->value,bytes,&e))error(env,&e);
        else {result=(*env)->NewByteArray(env,24);if(result)(*env)->SetByteArrayRegion(env,result,0,24,(jbyte *)bytes);}}
    ReleaseSRWLockExclusive(&registry);return result;
}
JNIEXPORT jlong JNICALL Java_dev_turboism_update_WindowsInstallationFiles_openFile(JNIEnv *env,jclass type,jlong token,jstring path,jlong limit){
    (void)type;if(limit<0){invalid(env,"negative size limit");return 0;}
    size_t length;WCHAR *p=string(env,path,&length);if(!p)return 0;
    jlong result=0;wf_error e;AcquireSRWLockExclusive(&registry);slot *s=lookup(env,token,ROOT);
    if(s){wf_file *file=0;if(!wf_file_open(s->value,p,length,(uint64_t)limit,&file,&e))error(env,&e);
        else {result=insert(env,file,FILE_TOKEN,(unsigned)(s-slots));if(!result)wf_file_close(file);}}
    ReleaseSRWLockExclusive(&registry);HeapFree(GetProcessHeap(),0,p);return result;
}
JNIEXPORT jlong JNICALL Java_dev_turboism_update_WindowsInstallationFiles_size(JNIEnv *env,jclass type,jlong token){
    (void)type;jlong result=0;AcquireSRWLockExclusive(&registry);slot *s=lookup(env,token,FILE_TOKEN);
    if(s)result=(jlong)wf_file_size(s->value);
    ReleaseSRWLockExclusive(&registry);return result;
}
JNIEXPORT jint JNICALL Java_dev_turboism_update_WindowsInstallationFiles_read(JNIEnv *env,jclass type,jlong token,jbyteArray bytes){
    (void)type;if(!bytes){invalid(env,"null read buffer");return 0;}
    jsize length=(*env)->GetArrayLength(env,bytes);
    if(length<=0||length>32768){invalid(env,"read buffer bounds");return 0;}
    unsigned char *buffer=HeapAlloc(GetProcessHeap(),0,(size_t)length);
    if(!buffer){wf_error e={ERROR_NOT_ENOUGH_MEMORY,0,"read allocation"};error(env,&e);return 0;}
    DWORD count=0;wf_error e;AcquireSRWLockExclusive(&registry);slot *s=lookup(env,token,FILE_TOKEN);
    if(s){if(!wf_file_read(s->value,buffer,(DWORD)length,&count,&e))error(env,&e);
        else if(count)(*env)->SetByteArrayRegion(env,bytes,0,(jsize)count,(jbyte *)buffer);}
    ReleaseSRWLockExclusive(&registry);HeapFree(GetProcessHeap(),0,buffer);return (jint)count;
}
JNIEXPORT void JNICALL Java_dev_turboism_update_WindowsInstallationFiles_finish(JNIEnv *env,jclass type,jlong token){
    (void)type;wf_error e;AcquireSRWLockExclusive(&registry);slot *s=lookup(env,token,FILE_TOKEN);
    if(s&&!wf_file_finish(s->value,&e))error(env,&e);
    ReleaseSRWLockExclusive(&registry);
}
JNIEXPORT void JNICALL Java_dev_turboism_update_WindowsInstallationFiles_closeToken(JNIEnv *env,jclass type,jlong token,jboolean root){
    (void)type;AcquireSRWLockExclusive(&registry);slot *s=lookup(env,token,root?ROOT:FILE_TOKEN);
    if(s){if(s->children)invalid(env,"root still borrowed");
        else {if(root)wf_root_close(s->value);else {wf_file_close(s->value);slots[s->parent].children--;}
              s->value=0;s->kind=0;}}
    ReleaseSRWLockExclusive(&registry);
}
BOOL WINAPI DllMain(HINSTANCE instance,DWORD reason,LPVOID reserved){(void)instance;(void)reason;(void)reserved;return TRUE;}
