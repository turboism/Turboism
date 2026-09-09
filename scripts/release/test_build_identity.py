import importlib.util,json,unittest
from pathlib import Path
P=Path(__file__).parent/'turboism_release'/'build_identity.py'
spec=importlib.util.spec_from_file_location('build_identity_tested',P);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class BuildIdentityTests(unittest.TestCase):
 def identity(self,run='123',attempt=1,version='0.44.0-beta.2'):
  return m.identity(version,'a'*40,run,attempt)
 def test_channels_share_one_counter(self):
  counter={'schemaVersion':1,'nextBuildNumber':1}
  for version,channel in [('0.44.0-beta.2','beta'),('0.44.0','stable'),('0.45.0-0.nightly.3','nightly')]:
   record,counter=m.reserve(counter,None,self.identity(version=version));self.assertEqual(record['channel'],channel)
  self.assertEqual(record['buildNumber'],3)
 def test_allocation_retry_is_idempotent_but_build_retry_is_new(self):
  record,counter=m.reserve({'schemaVersion':1,'nextBuildNumber':12},None,self.identity())
  again,unchanged=m.reserve(counter,record,self.identity());self.assertEqual(again,record);self.assertEqual(unchanged,counter)
  second,counter=m.reserve(counter,None,self.identity(attempt=2));self.assertEqual(second['buildNumber'],13)
 def test_same_run_different_source_cannot_rebind(self):
  a=self.identity();r,c=m.reserve({'schemaVersion':1,'nextBuildNumber':1},None,a);a['sourceRevision']='b'*40
  with self.assertRaises(ValueError):m.reserve(c,r,a)
 def test_invalid_identity_fails_before_writes(self):
  for v in ['1.2.3-beta.01','v1.2.3','1.2.3+meta','01.2.3']:
   with self.assertRaises(ValueError):self.identity(version=v)
  with self.assertRaises(ValueError):m.reserve({'schemaVersion':1,'nextBuildNumber':0},None,self.identity())
 def test_no_historical_number_is_synthesized(self):
  self.assertIsNone(m.read_optional_receipt(Path('/does-not-exist')))

class FakeGitHub:
 def __init__(self):self.head=None;self.trees={};self.commits={};self.serial=0;self.race=None
 def api(self,path,method='GET',data=None,optional=False):
  import base64
  self.serial+=1;new=f'{self.serial:040x}'
  if path=='git/ref/heads/build-ledger':return {'object':{'sha':self.head}} if self.head else None
  if path.startswith('git/commits/') and method=='GET':return self.commits[path.split('/')[-1]]
  if path.startswith('contents/'):
   name,ref=path[len('contents/'):].split('?ref=');commit=self.head if ref=='build-ledger' else ref
   value=self.trees[self.commits[commit]['tree']['sha']].get(name)
   if value is None:
    if optional:return None
    raise ValueError('404')
   return {'encoding':'base64','content':base64.b64encode(value.encode()).decode()}
  if path=='git/trees':
   tree=dict(self.trees.get(data.get('base_tree'),{}));tree.update({x['path']:x['content'] for x in data['tree']});self.trees[new]=tree;return {'sha':new}
  if path=='git/commits':self.commits[new]={'tree':{'sha':data['tree']},'parents':data['parents']};return {'sha':new}
  if path.startswith('git/refs'):
   if self.race:
    wanted=self.race;self.race=None;m.allocate(self,wanted)
   if path=='git/refs':
    if self.head:raise ValueError('already created')
   elif self.head not in self.commits[data['sha']]['parents'] or data.get('force') is not False:raise ValueError('non-fast-forward conflict')
   self.head=data['sha'];return {'object':{'sha':self.head}}
  raise AssertionError(path)
class AtomicBuildTests(unittest.TestCase):
 def test_concurrent_allocators_retry_without_losing_or_reusing_numbers(self):
  api=FakeGitHub();a=m.identity('1.2.3','a'*40,'10',1)
  self.assertEqual(m.allocate(api,a)['buildNumber'],1)
  api.race=m.identity('1.3.0-beta.1','b'*40,'11',1)
  result=m.allocate(api,m.identity('1.3.0-0.nightly.2','c'*40,'12',1));self.assertEqual(result['buildNumber'],3)
  self.assertEqual(m.allocate(api,a)['buildNumber'],1)
 def test_same_build_retries_return_persisted_identity(self):
  api=FakeGitHub();wanted=m.identity('1.2.3','a'*40,'42',1)
  first=m.allocate(api,wanted);head=api.head
  self.assertEqual(m.allocate(api,wanted),first);self.assertEqual(api.head,head)
 def test_rebuilt_attempt_gets_a_new_number(self):
  api=FakeGitHub();m.allocate(api,m.identity('1.2.3','a'*40,'42',1))
  self.assertEqual(m.allocate(api,m.identity('1.2.3','a'*40,'42',2))['buildNumber'],2)
