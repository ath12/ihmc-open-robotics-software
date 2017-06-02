package us.ihmc.simulationconstructionset.gui.actions.dialogActions;

import us.ihmc.simulationconstructionset.gui.SCSAction;
import us.ihmc.simulationconstructionset.gui.dialogConstructors.ResizeViewportDialogConstructor;
import java.awt.event.KeyEvent;

@SuppressWarnings("serial")
public class ResizeViewportAction extends SCSAction
{
   private ResizeViewportDialogConstructor constructor;

   public ResizeViewportAction(ResizeViewportDialogConstructor constructor)
   {
      super("Resize Viewport",
              "",
              KeyEvent.VK_V,
              "Short Description",
              "Long Description"
      );

      this.constructor = constructor;
   }

   @Override
   public void doAction()
   {
     constructor.constructDialog();
   }
}
