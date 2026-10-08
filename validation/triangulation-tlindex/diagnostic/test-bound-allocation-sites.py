import datetime
import importlib.util
from pathlib import Path
import unittest


def load(name, filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

analysis=load('allocation','analyze-bound-allocation-sites.py')
chain=load('chain','analyze-native-cache-refresh.py')
WINDOWS=[dict(cycle=i+1,startEpochMillis=1000+i*3000,endEpochMillis=2000+i*3000,javaThreadId=29) for i in range(3)]


def event(stamp, weight=100, frames=None, thread=29, truncated=False):
    return dict(type='jdk.ObjectAllocationSample',values=dict(startTime=datetime.datetime.fromtimestamp(stamp/1000,datetime.timezone.utc).isoformat(),weight=weight,eventThread=dict(javaThreadId=thread),objectClass=dict(name='java/lang/Object'),stackTrace=dict(truncated=truncated,frames=[dict(method=dict(type=dict(name=o.replace('.','/')),name=n,descriptor='()V'),bytecodeIndex=42) for o,n in (frames or [])])))


class AllocationSitesTest(unittest.TestCase):
    def valid(self):return [event(1500+i*3000,frames=chain.CHAIN) for i in range(3)]

    def test_disjoint_partition_and_overlapping_owners(self):
        helper=('dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTable','lookup')
        events=self.valid()+[event(1600,weight=50,frames=[helper]+chain.CHAIN)]
        r=analysis.summarize(events,WINDOWS,chain)['cycles'][0]
        self.assertEqual(150,r['sampledWeightBytes']['all'])
        self.assertEqual({'fullRedrawCacheChain':150},r['observedCallerPartitionWeightBytes'])
        self.assertEqual(150,r['inclusiveOwnerWeightBytes']['cacheRefresh'])
        self.assertEqual(50,r['inclusiveOwnerWeightBytes']['NativeMeshEdgeTable'])
        self.assertEqual(42,r['sites'][0]['frames'][0]['bci'])

    def test_strict_envelopes_and_thread_exclusion(self):
        r=analysis.summarize(self.valid()+[event(1000,weight=20),event(2000,weight=30),event(1500,weight=40,thread=9)],WINDOWS,chain)
        self.assertEqual(2,r['excluded']['outsideInvocationSamples'])
        self.assertEqual(50,r['excluded']['outsideInvocationWeightBytes'])
        self.assertEqual(40,r['excluded']['otherThreadWeightBytes'])
        self.assertEqual(100,r['cycles'][0]['sampledWeightBytes']['all'])

    def test_empty_and_truncated_evidence_remains_visible(self):
        r=analysis.summarize(self.valid()+[event(1600,weight=40,truncated=True)],WINDOWS,chain)['cycles'][0]
        self.assertEqual(40,r['sampledWeightBytes']['emptyStack'])
        self.assertEqual(40,r['sampledWeightBytes']['truncatedStack'])
        self.assertEqual(40,r['observedCallerPartitionWeightBytes']['otherOrUnknown'])

    def test_invalid_recordings_and_weights_refuse(self):
        for bad in [dict(type='jdk.DataLoss',values={}),event(9999,weight=-1),event(1600,weight=True)]:
            with self.subTest(bad=bad),self.assertRaises(ValueError):analysis.summarize(self.valid()+[bad],WINDOWS,chain)
        with self.assertRaisesRegex(ValueError,'missing'):analysis.summarize(self.valid()[:2],WINDOWS,chain)

    def test_bad_windows_refuse(self):
        for windows in [WINDOWS[:2],[dict(w,javaThreadId=30 if w['cycle']==2 else 29) for w in WINDOWS],[dict(w,startEpochMillis=1000,endEpochMillis=2000) for w in WINDOWS]]:
            with self.assertRaises(ValueError):analysis.summarize(self.valid(),windows,chain)


if __name__=='__main__':unittest.main()
