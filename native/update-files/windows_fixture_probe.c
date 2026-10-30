/* Opt-in fixture executable. Writes only a new explicitly supplied test directory.
   Not linked into JNI DLL or installed product. */
#include "windows_files.h"
static void output(const char *s){DWORD n=0;size_t len=0;while(s[len])len++;WriteFile(GetStdHandle(STD_OUTPUT_HANDLE),s,(DWORD)len,&n,0);}
static void require(int ok,const char *message){if(!ok){output("{\"status\":\"FAIL\",\"name\":\"");output(message);output("\"}\n");ExitProcess(1);}}
static size_t length(const WCHAR *s){size_t n=0;while(s[n])n++;return n;}
static void append(WCHAR *out,const WCHAR *a,const WCHAR *b){size_t i=0;while(*a)out[i++]=*a++;while(*b)out[i++]=*b++;out[i]=0;}
static void pass(const char *name){output("{\"status\":\"PASS\",\"name\":\"");output(name);output("\"}\n");}
typedef struct {const WCHAR *from;const WCHAR *to;HANDLE ready;HANDLE attempted;DWORD result;} rename_test;
static DWORD WINAPI rename_thread(void *value){rename_test *t=value;WaitForSingleObject(t->ready,5000);t->result=MoveFileW(t->from,t->to)?ERROR_SUCCESS:GetLastError();SetEvent(t->attempted);return 0;}
void wf_fixture_entry(void){
    WCHAR *path=HeapAlloc(GetProcessHeap(),0,60002),*leaf=HeapAlloc(GetProcessHeap(),0,61000),*moved=HeapAlloc(GetProcessHeap(),0,61000);
    require(path&&leaf&&moved,"allocation");
    DWORD count=GetEnvironmentVariableW(L"TURBOISM_W2_NATIVE_FIXTURE",path,30000);
    require(count>3&&count<29000,"explicit fixture root");
    require(CreateDirectoryW(path,0),"must create NEW fixture directory");
    append(leaf,path,L"\\leaf.bin");append(moved,path,L"-renamed");
    HANDLE h=CreateFileW(leaf,GENERIC_WRITE,0,0,CREATE_NEW,0,0);require(h!=INVALID_HANDLE_VALUE,"fixture leaf");
    DWORD written;require(WriteFile(h,"fixture",7,&written,0)&&written==7,"fixture write");CloseHandle(h);
    wf_error e;wf_root *root=0;wf_file *file=0;
    require(wf_root_open(path,length(path),&root,&e),"valid root");
    require(wf_file_open(root,L"leaf.bin",8,7,&file,&e),"valid leaf");
    unsigned char bytes[32];DWORD received;
    require(wf_file_read(file,bytes,sizeof(bytes),&received,&e)&&received==7,"bounded read");
    require(wf_file_read(file,bytes,sizeof(bytes),&received,&e)&&received==0&&wf_file_finish(file,&e),"stable EOF");
    h=CreateFileW(leaf,GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,0,OPEN_EXISTING,0,0);
    require(h==INVALID_HANDLE_VALUE&&GetLastError()==ERROR_SHARING_VIOLATION,"deny concurrent leaf WRITE");
    require(!MoveFileW(leaf,moved)&&GetLastError()==ERROR_SHARING_VIOLATION,"deny leaf replacement");
    wf_file_close(file);wf_root_close(root);pass("leaf-pin-read-and-conflict");
    h=CreateFileW(path,DELETE|FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,0,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS,0);
    require(h!=INVALID_HANDLE_VALUE,"preheld DELETE setup");
    require(!wf_root_open(path,length(path),&root,&e)&&e.win32==ERROR_SHARING_VIOLATION,"preheld directory DELETE rejected");
    CloseHandle(h);
    require(wf_root_open(path,length(path),&root,&e),"root reopened");
    h=CreateFileW(leaf,DELETE|FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,0,OPEN_EXISTING,0,0);
    require(h!=INVALID_HANDLE_VALUE,"preheld leaf DELETE setup");
    require(!wf_file_open(root,L"leaf.bin",8,7,&file,&e)&&e.win32==ERROR_SHARING_VIOLATION,"preheld leaf DELETE rejected");
    CloseHandle(h);pass("preheld-DELETE-conflicts");
    rename_test test={path,moved,CreateEventW(0,TRUE,FALSE,0),CreateEventW(0,TRUE,FALSE,0),0};
    require(test.ready&&test.attempted,"thread events");
    HANDLE thread=CreateThread(0,0,rename_thread,&test,0,0);require(thread!=0,"rename thread");
    SetEvent(test.ready);require(WaitForSingleObject(test.attempted,5000)==WAIT_OBJECT_0,"rename bounded wait");
    require(test.result==ERROR_SHARING_VIOLATION,"parent rename blocked while pinned");
    require(WaitForSingleObject(thread,5000)==WAIT_OBJECT_0,"rename thread finished");
    CloseHandle(thread);CloseHandle(test.ready);CloseHandle(test.attempted);wf_root_close(root);
    require(MoveFileW(path,moved),"root rename after close");require(MoveFileW(moved,path),"restore fixture name");pass("concurrent-parent-rename-and-close");
    DWORD before,after;require(GetProcessHandleCount(GetCurrentProcess(),&before),"handle count before");
    for(unsigned i=0;i<300;i++){
        require(wf_root_open(path,length(path),&root,&e),"repeated root open");
        require(!wf_file_open(root,L"missing",7,100,&file,&e),"missing leaf rejected");wf_root_close(root);
    }
    require(GetProcessHandleCount(GetCurrentProcess(),&after)&&after==before,"fault cleanup handle count");pass("fault-cleanup-no-handle-leak");
    HeapFree(GetProcessHeap(),0,path);HeapFree(GetProcessHeap(),0,leaf);HeapFree(GetProcessHeap(),0,moved);ExitProcess(0);
}
