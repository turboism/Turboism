"""Bounded class-file inspection; no JVM, host class loading or transformation."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'scripts/test'))
from sdk_api_baseline_model import ConstantPool, Reader


def attributes(reader, pool):
    result = []
    for _ in range(reader.u2()):
        result.append((pool.utf(reader.u2()), reader.take(reader.u4())))
    return result


def methods(data, label):
    reader = Reader(data, label)
    if reader.u4() != 0xCAFEBABE:
        raise ValueError('invalid class magic')
    reader.take(4)
    pool = ConstantPool(reader)
    reader.u2()
    name = pool.class_name(reader.u2())
    reader.u2()
    reader.take(reader.u2() * 2)
    for _ in range(reader.u2()):
        reader.take(6)
        attributes(reader, pool)
    result = []
    for _ in range(reader.u2()):
        access, method_name, descriptor = reader.u2(), pool.utf(reader.u2()), pool.utf(reader.u2())
        codes = [value for key, value in attributes(reader, pool) if key == 'Code']
        if len(codes) > 1:
            raise ValueError('duplicate Code attribute')
        if codes:
            code = Reader(codes[0], label + ':' + method_name + descriptor)
            max_stack, max_locals = code.u2(), code.u2()
            bytecode = code.take(code.u4())
            exceptions = [tuple(code.u2() for _ in range(4)) for _ in range(code.u2())]
            attributes(code, pool)
            if code.pos != len(code.data):
                raise ValueError('trailing Code attribute bytes')
            result.append({'name': method_name, 'descriptor': descriptor, 'access': access,
                           'maxStack': max_stack, 'maxLocals': max_locals,
                           'exceptions': exceptions, 'code': bytecode})
    attributes(reader, pool)
    if reader.pos != len(data):
        raise ValueError('trailing class bytes')
    return name, pool, result


def instructions(code, pool):
    """Decode every instruction, resolving member accesses without executing code."""
    result = []
    cursor = Reader(code, 'bytecode')
    two = {16, 18, *range(21, 26), *range(54, 59), 169, 188}
    three = {17, 19, 20, 132, *range(153, 169), *range(178, 185), 187, 189, 192, 193, 198, 199}
    members = {178: 'getstatic', 179: 'putstatic', 180: 'getfield', 181: 'putfield',
               182: 'invokevirtual', 183: 'invokespecial', 184: 'invokestatic', 185: 'invokeinterface'}
    branches = set(range(153, 169)) | {198, 199, 200, 201}
    while cursor.pos < len(code):
        offset, opcode = cursor.pos, cursor.u1()
        if opcode > 201:
            raise ValueError('reserved opcode')
        item = {'offset': offset, 'opcode': opcode}
        if opcode in (170, 171):
            padding = cursor.take((-cursor.pos) % 4)
            if any(padding):
                raise ValueError('nonzero switch padding')
            default = struct.unpack('>i', cursor.take(4))[0]
            item['defaultTarget'] = offset + default
            if opcode == 170:
                low, high = struct.unpack('>ii', cursor.take(8))
                if high < low or high - low + 1 > (len(code) - cursor.pos) // 4:
                    raise ValueError('invalid tableswitch')
                item['switchTargets'] = [offset + struct.unpack('>i', cursor.take(4))[0]
                                         for _ in range(high - low + 1)]
            else:
                count = struct.unpack('>i', cursor.take(4))[0]
                if count < 0 or count > (len(code) - cursor.pos) // 8:
                    raise ValueError('invalid lookupswitch')
                pairs = [struct.unpack('>ii', cursor.take(8)) for _ in range(count)]
                if any(a[0] >= b[0] for a, b in zip(pairs, pairs[1:])):
                    raise ValueError('unordered lookupswitch')
                item['switchTargets'] = [offset + target for _, target in pairs]
        elif opcode == 196:
            widened = cursor.u1()
            if widened not in {*range(21, 26), *range(54, 59), 132, 169}:
                raise ValueError('invalid wide instruction')
            cursor.take(4 if widened == 132 else 2)
        else:
            length = 5 if opcode in (185, 186, 200, 201) else 4 if opcode == 197 else 3 if opcode in three else 2 if opcode in two else 1
            operands = cursor.take(length - 1)
            if opcode in members:
                index = struct.unpack('>H', operands[:2])[0]
                _, owner, signature = pool.entry(index, 9 if opcode < 182 else (10, 11))
                _, method_name, descriptor = pool.entry(signature, 12)
                item.update(operation=members[opcode], owner=pool.class_name(owner),
                            name=pool.utf(method_name), descriptor=pool.utf(descriptor))
            elif opcode == 186:
                item['operation'] = 'invokedynamic'
            if opcode in branches:
                item['branchTarget'] = offset + int.from_bytes(operands, 'big', signed=True)
        item['bytes'] = code[offset:cursor.pos].hex()
        result.append(item)
    boundaries = {item['offset'] for item in result}
    for item in result:
        targets = item.get('switchTargets', []) + [item[key] for key in ('branchTarget', 'defaultTarget') if key in item]
        if any(target not in boundaries for target in targets):
            raise ValueError('branch target outside instruction boundaries')
    return result


def inspect(archive_path, selections):
    """Archive digest is intentionally not computed during another live host leg."""
    output = {}
    with zipfile.ZipFile(archive_path) as archive:
        for entry, selected_names in selections.items():
            if archive.namelist().count(entry) != 1:
                raise ValueError('missing/duplicate class entry: ' + entry)
            data = archive.read(entry)
            name, pool, parsed = methods(data, entry)
            selected = []
            for method in parsed:
                if method['name'] not in selected_names:
                    continue
                code = method.pop('code')
                method['codeSha256'] = hashlib.sha256(code).hexdigest()
                method['instructions'] = instructions(code, pool)
                selected.append(method)
            if not selected:
                raise ValueError('no selected code in ' + entry)
            output[name] = {'classSha256': hashlib.sha256(data).hexdigest(), 'methods': selected}
    return {'status': 'BYTECODE_INSPECTION_ONLY', 'classes': output, 'nativeExecuted': False,
            'archiveIdentityRevalidated': False,
            'limitations': ['Class bytes and code are hashed; complete JAR identity revalidation is deferred during the live performance leg.',
                            'Instruction/member listing is not a control-flow or whole-call-chain publication proof.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    selected = {
        'com/live2d/doc/IEditMode$b.class': {'a'},
        'com/live2d/cubism/doc/ACEditMode.class': {'endEdit'},
        'com/live2d/cubism/pack/CECompletePack.class': {'repaintCanvas'},
        'com/live2d/cubism/CEUpdateManager.class': {'repaintCanvas'},
        'com/live2d/util/aD.class': {'a', 'c'},
        'com/live2d/cubism/T.class': {'a', 'invoke'},
        'com/live2d/cubism/view/context/CEViewContext.class': {'onInputEvent'},
    }
    report = inspect(args.jar, selected)
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(report['status'])


if __name__ == '__main__':
    main()
