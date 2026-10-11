#!/usr/bin/env python3
"""Read-only integrity preflight for the frozen production-menu validation bundle."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verify(root):
    root=Path(root).absolute()
    def safe(p):
        if '..' in p.parts or any(x.is_symlink() for x in (p,*p.parents)):raise ValueError('linked/traversing bundle path')
        return p
    safe(root);m=json.loads((root/'bundle.json').read_text())
    profile=m.get('profile','cube')
    if 'editableTopologyInput' in m and (profile!='cat-cost-save' or m['editableTopologyInput'] is not True or m.get('externalResourceInput') or m.get('meshEditingTools') is not True):
        raise ValueError('editable topology requires the dedicated ZIP cost Cat save profile')
    if 'editableDragInput' in m and (profile!='cat-cost-drag' or m['editableDragInput'] is not True or any(m.get(k) for k in ('externalResourceInput','editableTopologyInput','meshEditingTools'))):
        raise ValueError('editable drag requires dedicated saved cost Cat seed without mesh editing probes')
    if 'editableDragTraceInput' in m and (m['editableDragTraceInput'] is not True or m.get('editableDragInput') is not True or profile!='cat-cost-drag'):
        raise ValueError('drag trace requires isolated editable cost drag input')
    if 'shaderUniformCandidate' in m and (m['shaderUniformCandidate'] is not True or m.get('editableDragTraceInput') is not True or m.get('editableDragInput') is not True or profile!='cat-cost-drag'):
        raise ValueError('shader uniform candidate requires isolated editable trace input')
    if 'shaderUniformProduct' in m and (m['shaderUniformProduct'] is not True or m.get('shaderUniformCandidate') is not None or m.get('editableDragTraceInput') is not True or m.get('editableDragInput') is not True or profile!='cat-cost-drag'):
        raise ValueError('production shader uniform validation requires the isolated editable trace input without the isolated candidate')
    if 'externalResourceInput' in m and (profile!='cat-cost-save' or m['externalResourceInput'] is not True):
        raise ValueError('separate resource input requires the dedicated cost Cat save profile')
    if 'rowMemoCandidate' in m and (profile!='cat-adaptive-short-drag' or m['rowMemoCandidate'] is not True or m.get('ellipseCoefficientCandidate') is not True or m.get('nativeDragAgent') is not True):
        raise ValueError('row memo requires explicit isolated adaptive native drag candidate')
    if profile in ('apple-bound-partition-reopen','apple-source-triangulation') and any(m.get(k) for k in ('xyzProbe','xyzSave','xyzReopen','placementProbe','meshEditingProbe','originalMeshEditing','meshEditingCommitSave','meshEditingTools','equivalentSlotProbe','partitionBoundProbe','meshStartIndex','meshEndIndex')):
        raise ValueError('bound reopen requires a dedicated read-only profile')
    if profile in ('apple-derived-atlas-undo','apple-derived-export','apple-derived-save','apple-derived-reopen') and any(m.get(k) for k in ('meshEditingTools','xyzProbe','xyzSave','xyzSaveOnly','xyzReopen','placementProbe','meshEditingProbe','originalMeshEditing','originalMeshReopen','meshEditingCommitSave','equivalentSlotProbe','partitionBoundProbe','meshStartIndex','meshEndIndex')):
        raise ValueError('derived persistence requires dedicated no-editor profile')
    if profile=='apple-derived-edit' and (m.get('meshEditingTools') is not True or any(m.get(k) for k in ('xyzProbe','xyzSave','xyzSaveOnly','xyzReopen','placementProbe','meshEditingProbe','originalMeshEditing','originalMeshReopen','meshEditingCommitSave','equivalentSlotProbe','partitionBoundProbe','meshStartIndex','meshEndIndex'))):
        raise ValueError('dedicated derived editor requires its native observer and no mixed probe actions')
    if profile in ('cat-vertex-drag-layout','cat-vertex-drag','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization') and (m.get('meshInterpolationCandidate') is not True or any(m.get(k) for k in ('xyzProbe','xyzSave','xyzReopen','placementProbe','meshEditingProbe','originalMeshEditing','meshEditingCommitSave','meshEditingTools'))):
        raise ValueError('Cat layout discovery requires explicit interpolation candidate and no combined editing probes')
    if 'meshInterpolationCandidate' in m and (profile not in ('cat-vertex-drag-layout','cat-vertex-drag','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization') or m['meshInterpolationCandidate'] is not True):
        raise ValueError('interpolation candidate is restricted to the dedicated Cat layout profile')
    if (profile in ('cat-vertex-drag','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag')) != (m.get('nativeDragAgent') is True):
        raise ValueError('native drag agent requires its dedicated Cat drag profile')
    if profile in ('cat-optimized-save','cat-adaptive-save','cat-cost-save','cat-optimized-reopen','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization','cat-default-serialization','cat-vertex-menu','cat-vertex-frames','cat-vertex-save','cat-vertex-reopen','cat-vertex-bank') and any(m.get(k) for k in ('xyzProbe','xyzSave','xyzReopen','placementProbe','meshEditingProbe','originalMeshEditing','meshEditingCommitSave','meshEditingTools') if not (k=='meshEditingTools' and m.get('editableTopologyInput'))):
        raise ValueError('dedicated Cat menu profile cannot combine other probes')
    if profile=='cat-vertex-edit' and (m.get('meshEditingTools') is not True or any(m.get(k) for k in ('xyzProbe','xyzSave','xyzReopen','placementProbe','meshEditingProbe','originalMeshEditing','meshEditingCommitSave'))):
        raise ValueError('Cat native editor diagnosis requires only its native mesh observer')
    if 'meshStartIndex' in m and (profile!='apple-all-mesh-edit' or type(m['meshStartIndex']) is not int or m['meshStartIndex']!=26 or m.get('meshEditingTools') is not True):
        raise ValueError('source chart start requires explicit remaining Apple editor profile')
    if 'meshEndIndex' in m and (profile!='apple-all-mesh-edit' or m.get('meshStartIndex')!=26
            or type(m['meshEndIndex']) is not int or m['meshEndIndex']!=27):
        raise ValueError('single chart settle diagnostic requires exact chart26 range')
    if 'equivalentSlotProbe' in m and (m['equivalentSlotProbe'] is not True or profile!='apple-all-mesh-edit'
            or m.get('meshStartIndex')!=26 or m.get('meshEndIndex')!=27 or m.get('meshEditingTools') is not True):
        raise ValueError('equivalent slot candidate requires dedicated chart26 native editor profile')
    if 'partitionBoundProbe' in m and (m['partitionBoundProbe'] is not True or m.get('equivalentSlotProbe') is not True or profile!='apple-all-mesh-edit' or m.get('meshStartIndex')!=26 or m.get('meshEndIndex')!=27):
        raise ValueError('bound partition save requires dedicated original chart26 source probe')
    if profile.startswith('interpolation-witness') and any(m.get(k) for k in ('xyzProbe','xyzSave','xyzReopen','placementProbe','meshEditingProbe','originalMeshEditing','meshEditingCommitSave','meshEditingTools')):
        raise ValueError('interpolation witness cannot be combined with other probes')
    if 'placementProbe' in m and (m['placementProbe'] is not True or profile not in ('cube','apple-menu-placement-compact') or m.get('xyzProbe') is not True
            or any(m.get(k) for k in ('xyzSave','xyzReopen','xyzSaveOnly','meshEditingProbe'))):
        raise ValueError('placement probe requires dedicated XYZ import without save')
    if profile=='apple-menu-placement-compact' and (m.get('placementProbe') is not True or m.get('xyzProbe') is not True):
        raise ValueError('apple placement requires explicit placement and XYZ probes')
    if 'xyzSave' in m and (m['xyzSave'] is not True or profile!='cube' or m.get('xyzProbe') is not True):
        raise ValueError('XYZ save requires the dedicated cube XYZ probe')
    if 'xyzReopen' in m and (m['xyzReopen'] is not True or profile!='cube' or m.get('xyzProbe') is not True or m.get('xyzSave')):
        raise ValueError('XYZ reopen requires a dedicated read-only cube XYZ probe')
    if 'xyzSaveOnly' in m and (m['xyzSaveOnly'] is not True or profile!='apple-menu-save-compact' or m.get('xyzProbe') is not True):
        raise ValueError('save-only diagnostic requires dedicated apple XYZ profile')
    mesh_tools=m.get('meshEditingTools',False)
    if 'originalMeshReopen' in m and (m['originalMeshReopen'] is not True or profile!='apple-menu-editability-compact'
            or any(m.get(k) for k in ('meshEditingProbe','originalMeshEditing','meshEditingCommitSave','meshEditingTools','xyzProbe'))):
        raise ValueError('committed mesh reopen requires a dedicated read-only authoring observation')
    if 'meshEditingCommitSave' in m and (m['meshEditingCommitSave'] is not True or m.get('originalMeshEditing') is not True):
        raise ValueError('mesh commit save requires dedicated original editing')
    if 'originalMeshEditing' in m and (m['originalMeshEditing'] is not True or profile!='apple-menu-editability-compact' or m.get('meshEditingProbe') is not True):
        raise ValueError('original mesh editing requires dedicated apple editability profile')
    if profile=='apple-all-mesh-edit' and (mesh_tools is not True or any(m.get(k) for k in ('meshEditingProbe','originalMeshEditing','xyzProbe','xyzSave','xyzReopen'))):
        raise ValueError('all-chart native observation requires its authorized mesh hook consumer only')
    if 'meshEditingTools' in m and (mesh_tools is not True or (profile not in ('apple-all-mesh-edit','apple-derived-edit','cat-vertex-edit') and m.get('meshEditingProbe') is not True and m.get('editableTopologyInput') is not True)):
        raise ValueError('mesh tools require the dedicated mesh editing probe')
    if 'xyzProbe' in m and (m['xyzProbe'] is not True or profile not in ('cube','apple-menu-placement-compact','apple-menu-save-compact','apple-menu-reopen-compact')):
        raise ValueError('XYZ probe requires the dedicated cube input profile')
    if 'meshEditingProbe' in m and (m['meshEditingProbe'] is not True or profile not in ('apple-menu-save-compact','apple-menu-reopen-compact','apple-menu-editability-compact')):
        raise ValueError('mesh editing probe requires an admitted compact apple profile')
    if profile in ('cat-adaptive-save','cat-adaptive-short-drag'):
        with zipfile.ZipFile(root/'bundle/plugins/model-import-3d-user-entry-probe.jar') as z:
            if hashlib.sha256(z.read('dev/turboism/validation/modelimport3d/host/cat-adaptive-sampling.properties')).hexdigest()!='8db03ba9306b21bcddd8262b2325ba4e5f437494c5f1b16c9a5a78ed75a26e32':raise ValueError('exact adaptive Cat sampling fixture required')
    if m.get('editableTopologyInput') or m.get('editableDragInput'):
        with zipfile.ZipFile(root/'bundle/plugins/model-import-3d-user-entry-probe.jar') as z:
            if hashlib.sha256(z.read('dev/turboism/validation/modelimport3d/host/cat-editable-sampling.properties')).hexdigest()!='245aeb45f8e620e052b51571f8695a0353108f2acc346a993e7800e6ca7bcb83':raise ValueError('exact editable Cat sampling fixture required')
    if profile in ('cat-cost-save','cat-cost-drag'):
        with zipfile.ZipFile(root/'bundle/plugins/model-import-3d-user-entry-probe.jar') as z:
            if hashlib.sha256(z.read('dev/turboism/validation/modelimport3d/host/cat-cost-sampling.properties')).hexdigest()!='16b613097ea9076426686042fca3e166cce89b7b1a710440114d0e4bf5e9746a':raise ValueError('exact cost Cat sampling fixture required')
    if profile not in ('apple-derived-atlas-undo','apple-derived-export','apple-derived-save','apple-derived-reopen','apple-derived-edit','apple-source-triangulation','apple-bound-partition-reopen','apple-all-mesh-edit','apple-xyz-export-bytes','apple-xyz-export','cat-optimized-save','cat-adaptive-save','cat-cost-save','cat-optimized-reopen','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization','cat-default-serialization','cat-vertex-menu','cat-vertex-frames','cat-vertex-save','cat-vertex-reopen','cat-vertex-bank','cat-vertex-edit','cat-vertex-drag-layout','cat-vertex-drag','cat-native-serialization','cat-default-serialization','interpolation-witness-save','interpolation-witness-reopen','interpolation-witness','plane-menu','seam-menu','plane-menu-save','seam-menu-save','plane-menu-reopen','seam-menu-reopen','plane-menu-atlas-save','seam-menu-atlas-save','plane-menu-atlas-reopen','seam-menu-atlas-reopen','menu-reject','cube','apple-layered','apple-menu-placement-compact','apple-menu-save','apple-menu-save-compact','apple-menu-reopen-compact','apple-menu-export-compact','apple-menu-atlas-save-compact','apple-menu-atlas-reopen-compact','apple-menu-editability-compact'):raise ValueError('unknown UI bundle profile')
    if m.get('xyzReopen') or profile in ('cat-native-serialization','cat-default-serialization','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-optimized-reopen','cat-vertex-reopen','cat-vertex-bank','cat-vertex-edit','cat-vertex-drag-layout','cat-vertex-drag','apple-derived-atlas-undo','apple-derived-export','apple-derived-save','apple-derived-reopen','apple-derived-edit','apple-source-triangulation','apple-bound-partition-reopen','apple-all-mesh-edit','apple-xyz-export-bytes','apple-xyz-export','interpolation-witness-reopen','plane-menu-reopen','seam-menu-reopen','plane-menu-atlas-save','seam-menu-atlas-save','plane-menu-atlas-reopen','seam-menu-atlas-reopen','apple-menu-reopen-compact','apple-menu-export-compact','apple-menu-atlas-save-compact','apple-menu-atlas-reopen-compact','apple-menu-editability-compact'):
        seed=safe(root/'seed.cmo3');row=m['reopenSeed'];limit=64*1024*1024 if profile in ('plane-menu-reopen','seam-menu-reopen','plane-menu-atlas-save','seam-menu-atlas-save','plane-menu-atlas-reopen','seam-menu-atlas-reopen') else 256*1024*1024
        if not seed.is_file() or not 0<seed.stat().st_size<=limit or seed.stat().st_size!=row['bytes']:raise ValueError('bounded reopen seed')
        digest=hashlib.sha256()
        with seed.open('rb') as stream:
            for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
        if digest.hexdigest()!=row['sha256']:raise ValueError('exact reopen seed identity')
    if (profile in ('cat-native-serialization','cat-default-serialization')) != (m.get('nativeSerializationAgent') is True):
        raise ValueError('native serialization agent requires its dedicated profile')
    if (profile in ('cat-native-serialization','cat-default-serialization')) != (m.get('serializationCandidate') is True):
        raise ValueError('serializer candidate requires the explicit dedicated profile')
    if profile=='cat-default-serialization' and m.get('defaultModelSerialization') is not True:raise ValueError('default model serialization admission required')
    if profile!='cat-default-serialization' and 'defaultModelSerialization' in m:raise ValueError('default serialization requires dedicated profile')
    if 'nativeImportWatchdog' in m and (profile not in ('cat-adaptive-save','cat-cost-save') or m['nativeImportWatchdog'] is not True):raise ValueError('import watchdog requires dedicated adaptive profile')
    expected={'turboism-agent.jar'  ,'plugins/model-import-3d.jar','plugins/model-import-3d-user-entry-probe.jar'}
    if m.get('nativeDragAgent'):
        expected.add('agents/cat-native-drag-agent.jar')
    if m.get('nativeSerializationAgent'):expected.add('agents/cat-native-serialization-agent.jar')
    if m.get('nativeImportWatchdog'):expected.add('agents/cat-import-watchdog-agent.jar')
    if mesh_tools:expected.add('plugins/mesh-edit-mirror-axis-enhance.jar')
    if m['status']!='FROZEN_NOT_HOST_VERIFIED' or m['hostExecuted'] is not False or set(m['files'])!=expected:raise ValueError('bundle protocol/inventory')
    actual={str(p.relative_to(root/'bundle')) for p in (root/'bundle').rglob('*') if safe(p).is_file()}
    if actual!=expected:raise ValueError('extra/missing staged artifact')
    for name,row in m['files'].items():
        p=safe(root/'bundle'/name)
        if p.stat().st_size!=row['bytes'] or p.stat().st_size>32*1024*1024 or hashlib.sha256(p.read_bytes()).hexdigest()!=row['sha256']:raise ValueError('artifact identity '+name)
    if m.get('nativeDragAgent'):
        with zipfile.ZipFile(root/'bundle/agents/cat-native-drag-agent.jar') as z:
            main='dev.turboism.validation.modelimport3d.nativeprobe.CatNativeDragAgent'
            if 'Premain-Class: '+main not in z.read('META-INF/MANIFEST.MF').decode('utf-8'):
                raise ValueError('exact native drag diagnostic entrypoint required')
            if any(n.endswith('.class') and not n.startswith('dev/turboism/validation/modelimport3d/nativeprobe/CatNativeDragAgent') for n in z.namelist()):
                raise ValueError('native drag diagnostic cannot contain host/runtime/SDK implementation classes')
    if m.get('nativeImportWatchdog'):
        with zipfile.ZipFile(root/'bundle/agents/cat-import-watchdog-agent.jar') as z:
            main='dev.turboism.validation.modelimport3d.nativeprobe.CatImportWatchdogAgent'
            if 'Premain-Class: '+main not in z.read('META-INF/MANIFEST.MF').decode('utf-8'):raise ValueError('exact read-only import observer entrypoint')
            if any(n.endswith('.class') and not n.startswith(main.replace('.','/')) for n in z.namelist()):raise ValueError('import observer cannot embed other implementations')
    if m.get('nativeSerializationAgent'):
        with zipfile.ZipFile(root/'bundle/agents/cat-native-serialization-agent.jar') as z:
            main='dev.turboism.validation.modelimport3d.nativeprobe.CatNativeSerializationAgent'
            if 'Premain-Class: '+main not in z.read('META-INF/MANIFEST.MF').decode('utf-8'):
                raise ValueError('exact native serialization entrypoint required')
            if any(n.endswith('.class') and not n.startswith('dev/turboism/validation/modelimport3d/nativeprobe/CatNativeSerializationAgent') for n in z.namelist()):
                raise ValueError('native serialization carrier cannot embed host/runtime/SDK implementation')
    if mesh_tools:
        with zipfile.ZipFile(root/'bundle/plugins/mesh-edit-mirror-axis-enhance.jar') as z:
            meta=json.loads(z.read('META-INF/turboism/plugin.json'))
            if (meta['id']!='dev.turboism.plugin.mesh-edit-mirror-axis-enhance'
                or meta['entrypoints']!=['dev.turboism.plugin.mesheditmirroraxisenhance.MeshEditMirrorAxisEnhancePlugin']
                or 'cubism.mesh.mirror-axis-angle' not in meta['capabilities']):
                raise ValueError('reviewed native mesh panel consumer required')
    for name,identity,entry in [('model-import-3d','dev.turboism.plugin.model-import-3d','ModelImportPlugin'),
                                ('model-import-3d-user-entry-probe','dev.turboism.validation.model-import-3d-user-entry','UserEntryProbe')]:
        with zipfile.ZipFile(root/'bundle/plugins'/f'{name}.jar') as z:
            if profile in ('apple-derived-atlas-undo','apple-derived-export','apple-derived-edit','apple-derived-save','apple-derived-reopen') and name=='model-import-3d-user-entry-probe':
                resource='dev/turboism/validation/modelimport3d/host/derived-bound-all98.bin'
                if hashlib.sha256(z.read(resource)).hexdigest() not in ('f93fc84f9a71351977ecd1d85e82d39793278b3683ffb3c8b1b32d5049ef3520','00a706b57d43b33e9042c0f1fb8b66692c0be44e7525a5b4ef4739be393656cc','cd891e99c35c3d0e6302c591a98fa020f2ae32dc6bba0a856a282f361afc54f2') or 'dev/turboism/validation/modelimport3d/host/AppleDerivedCapture.class' not in z.namelist():raise ValueError('exact derived capture fixture/driver required')
            if m.get('xyzProbe') and name=='model-import-3d':
                package='dev/turboism/validation/modelimport3d/host/'
                if package+'SurfaceRotationAuthoring.class' not in z.namelist():
                    raise ValueError('XYZ production plugin lacks native Roll authoring')
                entry_bytes=z.read(package+'ModelImportPlugin.class')
                if b'FULL_CIRCLE' not in entry_bytes or b'Param3DRoll' not in entry_bytes:
                    raise ValueError('XYZ production entry is not the full-circle three-axis build')
            if profile in ('cat-optimized-save','cat-adaptive-save','cat-cost-save','cat-optimized-reopen','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization','cat-default-serialization','cat-vertex-menu','cat-vertex-frames','cat-vertex-save','cat-vertex-reopen','cat-vertex-bank','cat-vertex-edit','cat-vertex-drag-layout','cat-vertex-drag') and name=='model-import-3d':
                prefix='dev/turboism/validation/modelimport3d/host/'
                if (m.get('editableTopologyInput') or m.get('editableDragInput')) and any(prefix+n+'.class' not in z.namelist() for n in ('SurfaceWarpImagePlan$EditableTopologyVertex','SurfaceEditableTopologyPlan','SurfaceNativeSourceWindingPlan')):
                    raise ValueError('editable topology requires the actual source refinement engine')
                if m.get('externalResourceInput') and any(prefix+n+'.class' not in z.namelist() for n in ('ModelInputFiles','ModelInputNormalizer$MissingResource')):
                    raise ValueError('separate resource input requires the production SDK resource resolver')
                if any(prefix+n+'.class' not in z.namelist() for n in ('SurfaceVertexPlan','SurfaceVertexAuthoring','SurfacePlacementAuthoring')):raise ValueError('Cat production direct authoring missing')
                if profile in ('cat-cost-save','cat-cost-drag') and prefix+'SurfaceImportCostPlan.class' not in z.namelist():raise ValueError('cost Cat requires actual deterministic cost planner')
                if profile in ('cat-optimized-save','cat-adaptive-save','cat-cost-save','cat-optimized-reopen','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization','cat-default-serialization') and any(prefix+n+'.class' not in z.namelist() for n in ('SurfaceSimplifier','SurfacePatchCollapseProof','ObjModelInput','ModelTextureNormalizer','ModelInputNormalizer')):raise ValueError('optimized Cat requires actual automatic input/optimization engine')
            if name=='model-import-3d-user-entry-probe' and m.get('equivalentSlotProbe'):
                candidate='dev/turboism/validation/modelimport3d/host/equivalent-chart26.bin'
                if hashlib.sha256(z.read(candidate)).hexdigest() not in ('adebbb4e934804d9642da62f2383d813aae1b4378e912426e5055ab35f412daa','906624bccb601f507a1531a9518c4efe9ab58027ac5a7eb9755ac8a0ba6fb1e1'):
                    raise ValueError('exact audited equivalent/partitioned chart26 fixture required')
                if m.get('partitionBoundProbe') and hashlib.sha256(z.read(candidate)).hexdigest()!='906624bccb601f507a1531a9518c4efe9ab58027ac5a7eb9755ac8a0ba6fb1e1':
                    raise ValueError('bound partition requires exact five-group source fixture')
                if 'dev/turboism/validation/modelimport3d/host/mesh-edit-copy.png' not in z.namelist():
                    raise ValueError('original apple source image for isolated editor required')
            meta=json.loads(z.read('META-INF/turboism/plugin.json'))
            if meta['id']!=identity or meta['entrypoints']!=['dev.turboism.validation.modelimport3d.host.'+entry]:raise ValueError('plugin entrypoint')
            entries=[item.filename for item in z.infolist() if not item.is_dir()]
            if len(entries)!=len(set(entries)):raise ValueError('duplicate plugin entry')
            base=meta['i18n']['baseName']
            catalogs={base+'.properties'} | {base+('' if locale=='base' else '_'+locale.replace('-','_'))+'.properties' for locale in meta['i18n']['locales']}
            actual_catalogs={n for n in entries if n==base+'.properties' or (n.startswith(base+'_') and n.endswith('.properties'))}
            if actual_catalogs!=catalogs:raise ValueError('plugin i18n catalog inventory')
            for resource in meta['resources']:
                if not any(n.startswith(resource) for n in entries):raise ValueError('missing declared plugin resource')
            for n in entries:
                if not (n.endswith('.class') or n.startswith('META-INF/') or any(n.startswith(resource) for resource in meta['resources'])):
                    raise ValueError('undeclared plugin resource '+n)
            if name=='model-import-3d-user-entry-probe' and 'profile' in m:
                if profile in ('cat-optimized-save','cat-adaptive-save','cat-cost-save','cat-optimized-reopen','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization','cat-default-serialization','cat-vertex-menu','cat-vertex-frames','cat-vertex-save','cat-vertex-reopen','cat-vertex-bank','cat-vertex-edit','cat-vertex-drag-layout','cat-vertex-drag'):
                    prefix='dev/turboism/validation/modelimport3d/host/'
                    resource='cat.zip' if profile in ('cat-optimized-save','cat-adaptive-save','cat-cost-save','cat-optimized-reopen','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization','cat-default-serialization') else 'cat.glb'
                    digest='ed1a27af5ee2724849d8c6ed3e76322579c3c88d92ecb11ffd99213ee0125d11' if profile in ('cat-optimized-save','cat-adaptive-save','cat-cost-save','cat-optimized-reopen','cat-optimized-drag','cat-optimized-short-drag','cat-adaptive-short-drag','cat-cost-drag','cat-native-serialization','cat-default-serialization') else '4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'
                    if hashlib.sha256(z.read(prefix+resource)).hexdigest()!=digest:raise ValueError('exact Cat input')
                    if any(prefix+n in entries for n in ('cube.glb','apple.glb','surface.glb')):raise ValueError('mixed Cat input')
                    if prefix+'CatVertexCapture.class' not in entries:raise ValueError('Cat capture missing')
                    if any(n.startswith(('dev/turboism/sdk/','dev/turboism/adapter/','com/live2d/')) for n in entries):raise ValueError('Cat driver contains host/runtime/SDK classes')
                    continue
                input_name='dev/turboism/validation/modelimport3d/host/'+('surface.glb' if profile in ('plane-menu','seam-menu','plane-menu-save','seam-menu-save','plane-menu-reopen','seam-menu-reopen','plane-menu-atlas-save','seam-menu-atlas-save','plane-menu-atlas-reopen','seam-menu-atlas-reopen') else 'apple.glb' if profile in ('apple-derived-atlas-undo','apple-derived-export','apple-derived-save','apple-derived-reopen','apple-derived-edit','apple-source-triangulation','apple-bound-partition-reopen','apple-all-mesh-edit','apple-xyz-export-bytes','apple-xyz-export','apple-layered','apple-menu-placement-compact','apple-menu-save','apple-menu-save-compact','apple-menu-reopen-compact','apple-menu-export-compact','apple-menu-atlas-save-compact','apple-menu-atlas-reopen-compact','apple-menu-editability-compact') else 'cube.glb')
                expected_sha=('8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997' if profile in ('plane-menu','plane-menu-save','plane-menu-reopen','plane-menu-atlas-save','plane-menu-atlas-reopen') else 'c9605e030d4f174501d8f98f798dcad3a5a3851254b347b1b80e882fee8484dd') if profile in ('plane-menu','seam-menu','plane-menu-save','seam-menu-save','plane-menu-reopen','seam-menu-reopen','plane-menu-atlas-save','seam-menu-atlas-save','plane-menu-atlas-reopen','seam-menu-atlas-reopen') else '3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999' if profile in ('apple-derived-atlas-undo','apple-derived-export','apple-derived-save','apple-derived-reopen','apple-derived-edit','apple-source-triangulation','apple-bound-partition-reopen','apple-all-mesh-edit','apple-xyz-export-bytes','apple-xyz-export','apple-layered','apple-menu-placement-compact','apple-menu-save','apple-menu-save-compact','apple-menu-reopen-compact','apple-menu-export-compact','apple-menu-atlas-save-compact','apple-menu-atlas-reopen-compact','apple-menu-editability-compact') else 'dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                if hashlib.sha256(z.read(input_name)).hexdigest()!=expected_sha:raise ValueError('UI input profile identity')
                other='dev/turboism/validation/modelimport3d/host/'+('cube.glb' if profile in ('apple-derived-atlas-undo','apple-derived-export','apple-derived-save','apple-derived-reopen','apple-derived-edit','apple-source-triangulation','apple-bound-partition-reopen','apple-all-mesh-edit','apple-xyz-export-bytes','apple-xyz-export','apple-layered','apple-menu-placement-compact','apple-menu-save','apple-menu-save-compact','apple-menu-reopen-compact','apple-menu-export-compact','apple-menu-atlas-save-compact','apple-menu-atlas-reopen-compact','apple-menu-editability-compact') else 'apple.glb')
                if other in entries or (profile in ('plane-menu','seam-menu','plane-menu-save','seam-menu-save','plane-menu-reopen','seam-menu-reopen','plane-menu-atlas-save','seam-menu-atlas-save','plane-menu-atlas-reopen','seam-menu-atlas-reopen') and 'dev/turboism/validation/modelimport3d/host/cube.glb' in entries):raise ValueError('mixed UI inputs')
                if profile in ('plane-menu-save','seam-menu-save','plane-menu-reopen','seam-menu-reopen','plane-menu-atlas-save','seam-menu-atlas-save','plane-menu-atlas-reopen','seam-menu-atlas-reopen','apple-menu-save','apple-menu-save-compact','apple-menu-reopen-compact','apple-menu-export-compact','apple-menu-atlas-save-compact','apple-menu-atlas-reopen-compact','apple-menu-editability-compact'):
                    permissions={p['id'] for p in meta['permissions']}
                    if not {'turboism.file.write','turboism.ui.file-chooser.request'}<=permissions or 'dev/turboism/validation/modelimport3d/host/ApplePersistenceFile.class' not in entries:raise ValueError('apple save consumer contract')
            if profile=='menu-reject' and name=='model-import-3d-user-entry-probe':
                for input_name,digest in (('intersecting-sheets.glb','dfae2a74e43fb58ac7faf76ab43230fa84293df8f1dc16017665a147da6232fc'),('cyclic-sheets.glb','6374ee20102596be9f0d9fb25536b9631ccb0202471acf125ffedbd2002f474a')):
                    if hashlib.sha256(z.read('dev/turboism/validation/modelimport3d/host/'+input_name)).hexdigest()!=digest:raise ValueError('exact rejection fixture')
            if any(n.startswith(('dev/turboism/sdk/','dev/turboism/adapter/','com/live2d/')) for n in z.namelist()):raise ValueError('plugin contains host/runtime/SDK classes')
    return m


if __name__=='__main__':
    verify(sys.argv[1]);print('BUNDLE_INTEGRITY_PASS_NOT_HOST_VERIFIED')
