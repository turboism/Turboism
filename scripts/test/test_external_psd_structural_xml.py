#!/usr/bin/env python3
"""Synthetic XML only: structural observation regressions, never real-host evidence."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

source = Path(__file__).resolve().parents[2] / "validation/external-psd-edit-host-probe/structural_xml.py"
spec = importlib.util.spec_from_file_location("structural_xml", source)
xml = importlib.util.module_from_spec(spec)
spec.loader.exec_module(xml)

AFFINE = '<CAffine xs.n="affine" m00="1" m01="0" m02="0" m10="0" m11="1" m12="0" />'
RECORD = '<CLayerInputData><CLayer xs.n="layer" xs.ref="#layer" />' + AFFINE + '<null xs.n="clippingOnTexturePx" /></CLayerInputData>'


def document(raw="raw", layer="layer", records=None, key="idstr"):
    records = [RECORD] if records is None else records
    return f'''<archive>
<CLayeredImage xs.id="#raw"><CLayeredImageGuid xs.n="guid" uuid="{raw}"/>
<CLayerGroup xs.n="_rootLayer" xs.ref="#root"/></CLayeredImage>
<CLayerGroup xs.id="#root"><CLayerGuid xs.n="guid" uuid="root"/>
<CLayeredImage xs.n="_layeredImage" xs.ref="#raw"/>
<carray_list xs.n="_children" count="1"><CLayer xs.ref="#layer"/></carray_list></CLayerGroup>
<CLayer xs.id="#layer"><ACImageLayer xs.n="super"><ACLayerEntry xs.n="super">
<CLayerGuid xs.n="guid" uuid="{layer}"/><CLayeredImage xs.n="_layeredImage" xs.ref="#raw"/>
</ACLayerEntry></ACImageLayer></CLayer>
<FilterValueId xs.id="#input" {key}="mi_input_layerInputData"/>
<CModelImage><CModelImageGuid xs.n="guid" uuid="image"/>
<ModelImageFilterEnv xs.n="inputFilterEnv"><FilterEnv xs.n="super">
<hash_map xs.n="envValues" count="2"><entry>
<FilterValueId xs.n="key" xs.ref="#input"/><EnvValueSet xs.n="value">
<CLayerSelectorMap xs.n="value"><linked_map xs.n="_imageToLayerInput" count="1"><entry>
<CLayeredImageGuid xs.n="key" uuid="{raw}"/><array_list xs.n="value" count="{len(records)}">
{''.join(records)}</array_list></entry></linked_map></CLayerSelectorMap></EnvValueSet></entry>
<entry><FilterValueId xs.n="key" idstr="mi_currentImageGuid"/><EnvValueSet xs.n="value">
<CLayeredImageGuid xs.n="value" uuid="{raw}"/></EnvValueSet></entry></hash_map></FilterEnv></ModelImageFilterEnv>
{AFFINE.replace('xs.n="affine"', 'xs.n="_materialLocalToCanvasTransform"')}
</CModelImage></archive>'''


class StructuralXmlTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.baseline = self.root / "baseline.xml"
        self.baseline.write_text(document())

    def observe(self, contents):
        saved = self.root / "saved.xml"
        saved.write_text(contents)
        return xml.saved_input_details(self.baseline, saved)

    def test_uses_real_idstr_key_and_preserves_null_clipping(self):
        value = self.observe(document())
        records = value["images"]["original:image"]["bindings"]["original:raw"]
        self.assertEqual("layer", records[0]["originalLayerGuid"])
        self.assertIsNone(records[0]["clipping"])
        self.assertEqual(float(1).hex(), records[0]["affine"][0])
        self.assertEqual("NOT_OBSERVED", value["liveUndoInputDetails"])
        with self.assertRaises(xml.InvalidStructure):
            self.observe(document(key="id"))

    def test_generated_guids_normalize_but_original_identity_does_not(self):
        self.assertEqual(self.observe(document("native", "new-a")),
                         self.observe(document("sdk", "new-b")))
        self.assertNotEqual(self.observe(document()), self.observe(document(layer="other")))

    def test_affine_clipping_and_record_order_are_visible(self):
        translated = RECORD.replace('m02="0"', 'm02="2"')
        clipped = RECORD.replace('<null xs.n="clippingOnTexturePx" />',
            '<CRect xs.n="clippingOnTexturePx"><i xs.n="x">1</i><i xs.n="y">2</i>'
            '<i xs.n="width">3</i><i xs.n="height">4</i></CRect>')
        baseline = self.observe(document())
        self.assertNotEqual(baseline, self.observe(document(records=[translated])))
        self.assertNotEqual(baseline, self.observe(document(records=[clipped])))
        self.assertNotEqual(self.observe(document(records=[RECORD, translated])),
                            self.observe(document(records=[translated, RECORD])))

    def test_empty_binding_is_preserved_and_missing_reference_rejected(self):
        empty = self.observe(document(records=[]))
        self.assertEqual([], empty["images"]["original:image"]["bindings"]["original:raw"])
        for bad in (document().replace('xs.ref="#layer"', 'xs.ref="#missing"'),
                    document().replace('count="2"', 'count="3"'),
                    document().replace('m00="1"', 'm00="NaN"'),
                    document().replace('uuid="raw"/><array_list', 'uuid="missing"/><array_list')):
            with self.assertRaises(xml.InvalidStructure):
                self.observe(bad)


if __name__ == "__main__":
    unittest.main()
