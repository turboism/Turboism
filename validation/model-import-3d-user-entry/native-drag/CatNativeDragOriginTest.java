package dev.turboism.validation.modelimport3d.nativeprobe;

import java.awt.event.MouseEvent;
import javax.swing.JPanel;

/** Official stateful drag wrapper regression: preserve press and previous points. */
public final class CatNativeDragOriginTest {
    public static void main(String[] args) throws Exception {
        Class<?> drag=CatNativeDragAgent.pinned("com.live2d.ui.event.l",CatNativeDragAgent.HOST_SHA);
        JPanel canvas=new JPanel();
        MouseEvent press=event(canvas,MouseEvent.MOUSE_PRESSED,266,640,MouseEvent.BUTTON1);
        Object gesture=drag.getConstructor(MouseEvent.class).newInstance(press);
        for(int x:new int[]{273,279,286}) {
            drag.getMethod("a",MouseEvent.class).invoke(gesture,event(canvas,MouseEvent.MOUSE_DRAGGED,x,640,MouseEvent.NOBUTTON));
            point(CatNativeDragAgent.call(gesture,"z"),266,640);
            point(gesture,x,640);
        }
        point(CatNativeDragAgent.call(gesture,"B"),279,640);
        drag.getMethod("b",MouseEvent.class).invoke(gesture,event(canvas,MouseEvent.MOUSE_RELEASED,286,640,MouseEvent.BUTTON1));
        point(CatNativeDragAgent.call(gesture,"z"),266,640);
        point(gesture,286,640);
        System.out.println("PASS official drag press origin remains 266,640 through three steps and release; displacement=20,0");
    }
    static MouseEvent event(JPanel canvas,int id,int x,int y,int button) {
        return new MouseEvent(canvas,id,1,id==MouseEvent.MOUSE_RELEASED?0:MouseEvent.BUTTON1_DOWN_MASK,
                              x,y,x+400,y+100,1,false,button);
    }
    static void point(Object wrapper,int x,int y) throws Exception {
        Object point=CatNativeDragAgent.call(wrapper,"K");
        if(!CatNativeDragAgent.call(point,"getX").equals(x)||!CatNativeDragAgent.call(point,"getY").equals(y))
            throw new AssertionError("official drag origin/current coordinate changed");
    }
}
