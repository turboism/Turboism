"""Read the pinned apple FBX 7.4 fixture; not a general FBX importer."""
import hashlib,struct,zlib
from pathlib import Path
SHA='fe402a4631e1f6f387637b3ce477919bfa3008e0e8acd14f51957e67ce3d11db'
def read(path):
    raw=Path(path).read_bytes()
    if hashlib.sha256(raw).hexdigest()!=SHA:raise ValueError('unauthorized FBX fixture')
    assert raw[:23]==b'Kaydara FBX Binary  \x00\x1a\x00' and struct.unpack_from('<I',raw,23)[0]==7400
    def node(pos,depth):
        assert depth<64
        end,count,size=struct.unpack_from('<III',raw,pos);length=raw[pos+12]
        if end==0:return None,pos+13
        assert pos<end<=len(raw) and count<10000
        name=raw[pos+13:pos+13+length].decode('utf-8');p=pos+13+length;start=p;props=[]
        for _ in range(count):
            kind=chr(raw[p]);p+=1
            if kind in 'YCFDIL':
                fmt={'Y':'h','C':'?','F':'f','D':'d','I':'i','L':'q'}[kind];v=struct.unpack_from('<'+fmt,raw,p)[0];p+=struct.calcsize(fmt)
            elif kind in 'SR':
                length=struct.unpack_from('<I',raw,p)[0];p+=4;v=raw[p:p+length];p+=length
                if kind=='S':v=v.decode('utf-8')
            elif kind in 'fdilbc':
                length,encoding,packed=struct.unpack_from('<III',raw,p);p+=12;data=raw[p:p+packed];p+=packed
                assert length<=1000000 and encoding in (0,1)
                if encoding:data=zlib.decompress(data)
                fmt={'f':'f','d':'d','i':'i','l':'q','b':'b','c':'b'}[kind];assert len(data)==length*struct.calcsize(fmt)
                v=[x[0] for x in struct.iter_unpack('<'+fmt,data)]
            else:raise ValueError('FBX property '+kind)
            props.append(v)
        assert p-start==size
        children=[]
        while p<end-13:
            child,p=node(p,depth+1)
            if child is not None:children.append(child)
        return dict(name=name,props=props,children=children),end
    result=[];p=27
    while struct.unpack_from('<I',raw,p)[0]:
        n,p=node(p,0);result.append(n)
    return result

def walk(nodes):
    for node in nodes:
        yield node
        yield from walk(node['children'])
