/* Original code. Only read operations. No JNI dependency, no CRT allocation/IO. */
#include "windows_files.h"
#include <winternl.h>
#include <aclapi.h>
#include <sddl.h>
#define WF_DEPTH 128
#define WF_NAME 240
#define WF_PATH 30000
#define WF_INFO 65536
struct wf_root {
    HANDLE pins[WF_DEPTH]; unsigned count; FILE_ID_INFO id;
    unsigned char user[SECURITY_MAX_SID_SIZE];
    unsigned char system[SECURITY_MAX_SID_SIZE];
    unsigned char admin[SECURITY_MAX_SID_SIZE];
    PSID installer;
};
struct wf_file {
    wf_root *root; HANDLE dirs[WF_DEPTH]; unsigned count; HANDLE handle;
    FILE_ID_INFO id; FILE_BASIC_INFO basic; uint64_t size; uint64_t position;
};
static void zero(void *p, size_t n) { unsigned char *b=p; while(n--) *b++=0; }
static void copy(void *to, const void *from, size_t n) {
    unsigned char *d=to; const unsigned char *s=from; while(n--) *d++=*s++;
}
static int same(const void *a, const void *b, size_t n) {
    const unsigned char *x=a,*y=b; while(n--) if(*x++!=*y++) return 0; return 1;
}
static int fail(wf_error *e, const char *op, DWORD code) {
    e->operation=op; e->win32=code; e->ntstatus=0; return 0;
}
static int ntfail(wf_error *e, const char *op, NTSTATUS status) {
    e->operation=op; e->ntstatus=status; e->win32=RtlNtStatusToDosError(status); return 0;
}
static int last(wf_error *e, const char *op) { DWORD code=GetLastError(); return fail(e,op,code); }
static int trusted(wf_root *r, PSID sid, int ancestor) {
    return EqualSid(sid,r->user)||EqualSid(sid,r->system)||EqualSid(sid,r->admin)
        ||(ancestor && r->installer && EqualSid(sid,r->installer));
}
/* Conservative admission, not an AccessCheck emulation. Unknown ACE types fail closed.
   INHERIT_ONLY is not effective on this object. Deny ACEs never grant authority. */
static int security(wf_root *r, HANDLE h, int ancestor, wf_error *e) {
    PSECURITY_DESCRIPTOR sd=0; PSID owner=0; PACL acl=0;
    DWORD result=GetSecurityInfo(h,SE_FILE_OBJECT,OWNER_SECURITY_INFORMATION|DACL_SECURITY_INFORMATION,
                                &owner,0,&acl,0,&sd);
    if(result!=ERROR_SUCCESS) return fail(e,"GetSecurityInfo",result);
    int ok=0;
    if(!IsValidSecurityDescriptor(sd)||!owner||!IsValidSid(owner)||!trusted(r,owner,ancestor)||!acl||!IsValidAcl(acl)) {
        fail(e,"owner/DACL",ERROR_ACCESS_DENIED); goto done;
    }
    GENERIC_MAPPING mapping={FILE_GENERIC_READ,FILE_GENERIC_WRITE,FILE_GENERIC_EXECUTE,FILE_ALL_ACCESS};
    DWORD forbidden=DELETE|WRITE_DAC|WRITE_OWNER|FILE_DELETE_CHILD;
    if(!ancestor) forbidden|=FILE_WRITE_DATA|FILE_APPEND_DATA|FILE_WRITE_EA|FILE_WRITE_ATTRIBUTES;
    for(DWORD i=0;i<acl->AceCount;i++) {
        void *raw=0;
        if(!GetAce(acl,i,&raw)) {last(e,"GetAce");goto done;}
        ACE_HEADER *header=raw;
        if(header->AceType!=ACCESS_ALLOWED_ACE_TYPE&&header->AceType!=ACCESS_DENIED_ACE_TYPE) {
            fail(e,"unknown ACE",ERROR_ACCESS_DENIED);goto done;
        }
        if(header->AceSize<FIELD_OFFSET(ACCESS_ALLOWED_ACE,SidStart)+8) {fail(e,"ACE size",ERROR_INVALID_DATA);goto done;}
        ACCESS_ALLOWED_ACE *ace=raw; PSID sid=&ace->SidStart;
        DWORD available=(DWORD)(header->AceSize-FIELD_OFFSET(ACCESS_ALLOWED_ACE,SidStart));
        DWORD required=8+4*(DWORD)((const unsigned char *)sid)[1];
        if(required>available||!IsValidSid(sid)||GetLengthSid(sid)!=required) {
            fail(e,"ACE SID",ERROR_INVALID_DATA);goto done;
        }
        DWORD mask=ace->Mask; MapGenericMask(&mask,&mapping);
        if(!(header->AceFlags&INHERIT_ONLY_ACE)&&header->AceType==ACCESS_ALLOWED_ACE_TYPE
           &&(mask&forbidden)&&!trusted(r,sid,ancestor)) {fail(e,"untrusted mutation ACE",ERROR_ACCESS_DENIED);goto done;}
    }
    ok=1;
done: LocalFree(sd); return ok;
}
static int streams(HANDLE h, wf_error *e) {
    void *buffer=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,WF_INFO);
    if(!buffer) return fail(e,"stream allocation",ERROR_NOT_ENOUGH_MEMORY);
    IO_STATUS_BLOCK io; zero(&io,sizeof(io));
    NTSTATUS status=NtQueryInformationFile(h,&io,buffer,WF_INFO,FileStreamInformation);
    int ok=0;
    if(status<0) {ntfail(e,"NtQueryInformationFile(streams)",status);goto done;}
    if(io.Information>WF_INFO) {fail(e,"stream bounds",ERROR_INVALID_DATA);goto done;}
    size_t offset=0, used=(size_t)io.Information;
    while(offset<used) {
        if(used-offset<FIELD_OFFSET(FILE_STREAM_INFORMATION,StreamName)) {fail(e,"stream bounds",ERROR_INVALID_DATA);goto done;}
        FILE_STREAM_INFORMATION *s=(void *)((unsigned char *)buffer+offset);
        static const WCHAR unnamed[]=L"::$DATA";
        if(s->StreamNameLength!=sizeof(unnamed)-sizeof(WCHAR)
           ||s->StreamNameLength>used-offset-FIELD_OFFSET(FILE_STREAM_INFORMATION,StreamName)
           ||!same(s->StreamName,unnamed,s->StreamNameLength)) {fail(e,"named stream",ERROR_ACCESS_DENIED);goto done;}
        if(!s->NextEntryOffset) {ok=1;goto done;}
        if(s->NextEntryOffset<FIELD_OFFSET(FILE_STREAM_INFORMATION,StreamName)+s->StreamNameLength
           ||s->NextEntryOffset%8||s->NextEntryOffset>=used-offset) {fail(e,"stream offset",ERROR_INVALID_DATA);goto done;}
        offset+=s->NextEntryOffset;
    }
    ok=1;
done: HeapFree(GetProcessHeap(),0,buffer);return ok;
}
static int inspect(wf_root *r,HANDLE h,int directory,int ancestor,FILE_ID_INFO *id, wf_error *e) {
    FILE_ATTRIBUTE_TAG_INFO tag; FILE_STANDARD_INFO standard;
    if(!GetFileInformationByHandleEx(h,FileAttributeTagInfo,&tag,sizeof(tag))
       ||!GetFileInformationByHandleEx(h,FileStandardInfo,&standard,sizeof(standard))
       ||!GetFileInformationByHandleEx(h,FileIdInfo,id,sizeof(*id))) return last(e,"handle information");
    if((tag.FileAttributes&FILE_ATTRIBUTE_REPARSE_POINT)||tag.ReparseTag||standard.DeletePending
       ||!!standard.Directory!=!!directory||(!directory&&standard.NumberOfLinks!=1))
        return fail(e,"type/reparse/link",ERROR_ACCESS_DENIED);
    if(r->count&&id->VolumeSerialNumber!=r->id.VolumeSerialNumber) return fail(e,"volume changed",ERROR_ACCESS_DENIED);
    return security(r,h,ancestor,e)&&streams(h,e);
}
/* Windows components are deliberately narrower than NT object names. */
static int component(const WCHAR *s,size_t n,wf_error *e) {
    if(!n||n>WF_NAME||s[n-1]==L'.'||s[n-1]==L' ') return fail(e,"component length/suffix",ERROR_INVALID_NAME);
    for(size_t i=0;i<n;i++) {
        WCHAR c=s[i];
        if(c<32||c==L':'||c==L'/'||c==L'\\'||c==L'"'||c==L'<'||c==L'>'||c==L'|'||c==L'?'||c==L'*'
           ||(c>=0xD800&&c<=0xDFFF)) return fail(e,"component characters",ERROR_INVALID_NAME);
    }
    size_t stem=0;while(stem<n&&s[stem]!=L'.')stem++;
    static const WCHAR *names[]={L"CON",L"PRN",L"AUX",L"NUL",L"CONIN$",L"CONOUT$"};
    for(unsigned i=0;i<sizeof(names)/sizeof(names[0]);i++)
        if(CompareStringOrdinal(s,(int)stem,names[i],-1,TRUE)==CSTR_EQUAL) return fail(e,"device component",ERROR_INVALID_NAME);
    if(stem==4&&(CompareStringOrdinal(s,3,L"COM",3,TRUE)==CSTR_EQUAL||CompareStringOrdinal(s,3,L"LPT",3,TRUE)==CSTR_EQUAL)
       &&((s[3]>=L'0'&&s[3]<=L'9')||s[3]==0xB9||s[3]==0xB2||s[3]==0xB3)) return fail(e,"device component",ERROR_INVALID_NAME);
    return 1;
}
static int relative(HANDLE parent,const WCHAR *s,size_t n,int directory,int ignorecase,HANDLE *out,wf_error *e) {
    if(!component(s,n,e))return 0;
    UNICODE_STRING name={(USHORT)(n*2),(USHORT)(n*2),(PWSTR)s};
    OBJECT_ATTRIBUTES attributes;zero(&attributes,sizeof(attributes));
    attributes.Length=sizeof(attributes);attributes.RootDirectory=parent;attributes.ObjectName=&name;
    attributes.Attributes=OBJ_DONT_REPARSE|(ignorecase?OBJ_CASE_INSENSITIVE:0);
    IO_STATUS_BLOCK io;zero(&io,sizeof(io));
    NTSTATUS status=NtCreateFile(out,READ_CONTROL|SYNCHRONIZE|FILE_READ_ATTRIBUTES|(directory?FILE_LIST_DIRECTORY:FILE_READ_DATA),
        &attributes,&io,0,0,FILE_SHARE_READ|(directory?FILE_SHARE_WRITE:0),FILE_OPEN,
        FILE_OPEN_REPARSE_POINT|FILE_SYNCHRONOUS_IO_NONALERT|(directory?FILE_DIRECTORY_FILE:FILE_NON_DIRECTORY_FILE),0,0);
    if(status<0)return ntfail(e,"NtCreateFile",status);
    FILE_NAME_INFO *info=HeapAlloc(GetProcessHeap(),0,WF_INFO);
    if(!info){CloseHandle(*out);*out=0;return fail(e,"name allocation",ERROR_NOT_ENOUGH_MEMORY);}
    int ok=GetFileInformationByHandleEx(*out,FileNameInfo,info,WF_INFO);
    if(!ok)last(e,"FileNameInfo");
    else {
        size_t len=info->FileNameLength/2,start=len;
        if(info->FileNameLength%2||len>(WF_INFO-FIELD_OFFSET(FILE_NAME_INFO,FileName))/2)ok=0;
        else {while(start&&info->FileName[start-1]!=L'\\')start--;
              ok=CompareStringOrdinal(info->FileName+start,(int)(len-start),s,(int)n,ignorecase)==CSTR_EQUAL;}
        if(!ok)fail(e,"long name/alias",ERROR_INVALID_NAME);
    }
    HeapFree(GetProcessHeap(),0,info);
    if(!ok){CloseHandle(*out);*out=0;}return ok;
}
void wf_root_close(wf_root *r) {
    if(!r)return;
    while(r->count)CloseHandle(r->pins[--r->count]);
    if(r->installer)LocalFree(r->installer);
    HeapFree(GetProcessHeap(),0,r);
}
int wf_root_open(const WCHAR *path,size_t length,wf_root **out,wf_error *e) {
    *out=0;
    if(length<4||length>WF_PATH||path[1]!=L':'||path[2]!=L'\\'
       ||!((path[0]>=L'A'&&path[0]<=L'Z')||(path[0]>=L'a'&&path[0]<=L'z')))
        return fail(e,"absolute local drive path",ERROR_INVALID_NAME);
    WCHAR drive[]={path[0],L':',L'\\',0}, dos[]={path[0],L':',0}, target[256];
    if(GetDriveTypeW(drive)!=DRIVE_FIXED)return fail(e,"fixed drive required",ERROR_ACCESS_DENIED);
    if(!QueryDosDeviceW(dos,target,256))return last(e,"QueryDosDevice");
    /* Reject SUBST and other DOS aliases. Mounted local fixed volumes map to HarddiskVolume. */
    if(CompareStringOrdinal(target,22,L"\\Device\\HarddiskVolume",22,FALSE)!=CSTR_EQUAL)
        return fail(e,"drive alias",ERROR_ACCESS_DENIED);
    wf_root *r=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,sizeof(*r));
    if(!r)return fail(e,"root allocation",ERROR_NOT_ENOUGH_MEMORY);
    HANDLE token=0;DWORD size=0;void *user=0; int ok=0;
    if(!OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token)){last(e,"OpenProcessToken");goto done;}
    GetTokenInformation(token,TokenUser,0,0,&size);
    if(GetLastError()!=ERROR_INSUFFICIENT_BUFFER||size>65536){fail(e,"TokenUser size",ERROR_INVALID_DATA);goto done;}
    user=HeapAlloc(GetProcessHeap(),0,size);
    if(!user){fail(e,"token allocation",ERROR_NOT_ENOUGH_MEMORY);goto done;}
    if(!GetTokenInformation(token,TokenUser,user,size,&size)||!CopySid(sizeof(r->user),r->user,((TOKEN_USER *)user)->User.Sid)){
        last(e,"TokenUser");goto done;
    }
    size=sizeof(r->system);if(!CreateWellKnownSid(WinLocalSystemSid,0,r->system,&size)){last(e,"SYSTEM SID");goto done;}
    size=sizeof(r->admin);if(!CreateWellKnownSid(WinBuiltinAdministratorsSid,0,r->admin,&size)){last(e,"Admin SID");goto done;}
    if(!ConvertStringSidToSidW(L"S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464",&r->installer)){
        last(e,"TrustedInstaller SID");goto done;
    }
    HANDLE h=CreateFileW(drive,FILE_LIST_DIRECTORY|FILE_READ_ATTRIBUTES|READ_CONTROL|SYNCHRONIZE,
        FILE_SHARE_READ|FILE_SHARE_WRITE,0,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,0);
    if(h==INVALID_HANDLE_VALUE){last(e,"open drive root");goto done;}
    r->pins[r->count++]=h;
    WCHAR fs[32];
    if(!GetVolumeInformationByHandleW(h,0,0,0,0,0,fs,32)){last(e,"volume information");goto done;}
    if(CompareStringOrdinal(fs,-1,L"NTFS",-1,FALSE)!=CSTR_EQUAL){fail(e,"NTFS required",ERROR_NOT_SUPPORTED);goto done;}
    r->count=0;
    int inspected=inspect(r,h,1,1,&r->id,e);r->count=1;if(!inspected)goto done;
    size_t pos=3;
    while(pos<length){
        size_t end=pos;while(end<length&&path[end]!=L'\\')end++;
        if(r->count>=WF_DEPTH){fail(e,"root depth",ERROR_BUFFER_OVERFLOW);goto done;}
        if(!relative(r->pins[r->count-1],path+pos,end-pos,1,1,&h,e))goto done;
        r->pins[r->count++]=h;FILE_ID_INFO id;
        if(!inspect(r,h,1,end<length,&id,e))goto done;
        pos=end+1;if(pos==length){fail(e,"root trailing separator",ERROR_INVALID_NAME);goto done;}
    }
    if(!GetFileInformationByHandleEx(r->pins[r->count-1],FileIdInfo,&r->id,sizeof(r->id))){last(e,"root ID");goto done;}
    ok=1;*out=r;
done:
    if(user)HeapFree(GetProcessHeap(),0,user);
    if(token)CloseHandle(token);
    if(!ok)wf_root_close(r);
    return ok;
}
int wf_root_identity(wf_root *r,unsigned char out[24],wf_error *e) {
    FILE_ID_INFO id;
    if(!inspect(r,r->pins[r->count-1],1,0,&id,e))return 0;
    if(!same(&id,&r->id,sizeof(id)))return fail(e,"root identity changed",ERROR_ACCESS_DENIED);
    copy(out,&id.VolumeSerialNumber,8);copy(out+8,id.FileId.Identifier,16);return 1;
}
void wf_file_close(wf_file *f) {
    if(!f)return;
    if(f->handle)CloseHandle(f->handle);
    while(f->count)CloseHandle(f->dirs[--f->count]);
    HeapFree(GetProcessHeap(),0,f);
}
uint64_t wf_file_size(wf_file *f){return f->size;}
int wf_file_open(wf_root *r,const WCHAR *path,size_t length,uint64_t limit,wf_file **out,wf_error *e) {
    *out=0;
    if(!length||length>WF_PATH)return fail(e,"relative path length",ERROR_INVALID_NAME);
    wf_file *f=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,sizeof(*f));
    if(!f)return fail(e,"file allocation",ERROR_NOT_ENOUGH_MEMORY);
    f->root=r;HANDLE parent=r->pins[r->count-1];size_t pos=0;int ok=0;
    while(pos<length){
        size_t end=pos;while(end<length&&path[end]!=L'/'&&path[end]!=L'\\')end++;
        int directory=end<length;HANDLE h=0;
        if(directory&&f->count>=WF_DEPTH){fail(e,"relative depth",ERROR_BUFFER_OVERFLOW);goto done;}
        if(!relative(parent,path+pos,end-pos,directory,0,&h,e))goto done;
        if(directory)f->dirs[f->count++]=h;else f->handle=h;
        FILE_ID_INFO id;if(!inspect(r,h,directory,0,&id,e))goto done;
        if(!directory){
            FILE_STANDARD_INFO standard;
            if(!GetFileInformationByHandleEx(h,FileStandardInfo,&standard,sizeof(standard))
               ||!GetFileInformationByHandleEx(h,FileBasicInfo,&f->basic,sizeof(f->basic))){last(e,"file initial state");goto done;}
            if(standard.EndOfFile.QuadPart<0||(uint64_t)standard.EndOfFile.QuadPart>limit){fail(e,"file size limit",ERROR_FILE_TOO_LARGE);goto done;}
            f->size=(uint64_t)standard.EndOfFile.QuadPart;f->id=id;
        }
        parent=h;pos=end+1;
        if(directory&&pos==length){fail(e,"empty component",ERROR_INVALID_NAME);goto done;}
    }
    ok=1;*out=f;
done:if(!ok)wf_file_close(f);return ok;
}
int wf_file_read(wf_file *f,unsigned char *bytes,DWORD capacity,DWORD *read,wf_error *e){
    if(!capacity||capacity>32768)return fail(e,"read capacity",ERROR_INVALID_PARAMETER);
    if(!ReadFile(f->handle,bytes,capacity,read,0))return last(e,"ReadFile");
    if(*read>f->size-f->position)return fail(e,"read growth",ERROR_INVALID_DATA);
    f->position+=*read;return 1;
}
int wf_file_finish(wf_file *f,wf_error *e){
    FILE_ID_INFO id;FILE_BASIC_INFO basic;FILE_STANDARD_INFO standard;
    if(!inspect(f->root,f->handle,0,0,&id,e))return 0;
    if(!GetFileInformationByHandleEx(f->handle,FileBasicInfo,&basic,sizeof(basic))
       ||!GetFileInformationByHandleEx(f->handle,FileStandardInfo,&standard,sizeof(standard)))return last(e,"file final state");
    if(f->position!=f->size||(uint64_t)standard.EndOfFile.QuadPart!=f->size||!same(&id,&f->id,sizeof(id))
       ||basic.LastWriteTime.QuadPart!=f->basic.LastWriteTime.QuadPart
       ||basic.ChangeTime.QuadPart!=f->basic.ChangeTime.QuadPart)return fail(e,"file changed/incomplete",ERROR_INVALID_DATA);
    return 1;
}
