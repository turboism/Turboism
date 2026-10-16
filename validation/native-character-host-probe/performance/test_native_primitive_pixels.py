"""Small semantic checks: identical texture colors must not conceal incorrect depth order."""
import csv
import tempfile
import unittest
from pathlib import Path
import numpy as np
from PIL import Image
from check_native_primitive_pixels import compare

class NativeOrderGateTest(unittest.TestCase):
    def check_order(self, orders):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'source';poses=root/'poses';out=root/'out'
            for p in (source,poses,out):p.mkdir()
            xy=((2,2),(2,10),(10,2))
            def write(path,header,rows):
                with path.open('w') as f:
                    writer=csv.writer(f);writer.writerow(header);writer.writerows(rows)
            write(source/'source-plan.vertices.csv',('partition','slot','x','y','z','u','v'),
                  [(p,i,x,y,1-p,.5,.5) for p in range(2) for i,(x,y) in enumerate(xy)])
            write(source/'source-plan.triangles.csv',('partition','a','b','c','doubleSided'),
                  [(p,0,1,2,'false') for p in range(2)])
            label='yaw0.0-pitch0.0'
            write(poses/(label+'.vertices.csv'),('mesh','order','coordinateType','slot','x','y','u','v'),
                  [(f'Mesh{p}',orders[p],'Canvas',i,x,y,.5,.5) for p in range(2) for i,(x,y) in enumerate(xy)])
            write(poses/(label+'.triangles.csv'),('mesh','a','b','c'),[(f'Mesh{p}',0,2,1) for p in range(2)])
            Image.new('RGBA',(4,4),(255,0,0,255)).save(root/'atlas.png')
            pixels=np.zeros((12,12,4),dtype=np.uint8)
            for y in range(12):
                for x in range(12):
                    if x+.5>=2 and y+.5>=2 and x+y+1<=12:pixels[y,x]=[255,0,0,255]
            Image.fromarray(pixels).save(poses/(label+'.png'))
            return compare(source,poses,root/'atlas.png',out,1,'order-interiors')

    def test_front_order_passes(self):self.assertTrue(self.check_order((200,100)))
    def test_identically_colored_wrong_front_fails(self):self.assertFalse(self.check_order((100,200)))
    def test_equal_integer_order_is_not_assumed(self):self.assertFalse(self.check_order((100,100)))

if __name__=='__main__':unittest.main()
