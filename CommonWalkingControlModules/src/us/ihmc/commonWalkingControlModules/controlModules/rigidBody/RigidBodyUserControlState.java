package us.ihmc.commonWalkingControlModules.controlModules.rigidBody;

import gnu.trove.map.hash.TObjectDoubleHashMap;
import us.ihmc.commonWalkingControlModules.controllerCore.command.feedbackController.FeedbackControlCommand;
import us.ihmc.commonWalkingControlModules.controllerCore.command.inverseDynamics.InverseDynamicsCommand;
import us.ihmc.commonWalkingControlModules.controllerCore.command.inverseDynamics.JointspaceAccelerationCommand;
import us.ihmc.commons.PrintTools;
import us.ihmc.humanoidRobotics.communication.controllerAPI.command.DesiredAccelerationCommand;
import us.ihmc.yoVariables.registry.YoVariableRegistry;
import us.ihmc.yoVariables.variable.BooleanYoVariable;
import us.ihmc.yoVariables.variable.DoubleYoVariable;
import us.ihmc.robotics.screwTheory.OneDoFJoint;

public class RigidBodyUserControlState extends RigidBodyControlState
{
   public static final double TIME_WITH_NO_MESSAGE_BEFORE_ABORT = 0.25;

   private final JointspaceAccelerationCommand jointspaceAccelerationCommand;

   private final OneDoFJoint[] jointsToControl;
   private final int numberOfJoints;

   private final DoubleYoVariable[] userDesiredJointAccelerations;
   private final DoubleYoVariable[] weights;

   private final BooleanYoVariable abortUserControlMode;
   private final BooleanYoVariable hasWeights;

   public RigidBodyUserControlState(String bodyName, OneDoFJoint[] jointsToControl, DoubleYoVariable yoTime, YoVariableRegistry parentRegistry)
   {
      super(RigidBodyControlMode.USER, bodyName, yoTime, parentRegistry);
      String prefix = bodyName + "UserMode";
      hasWeights = new BooleanYoVariable(prefix + "HasWeights", registry);

      this.jointsToControl = jointsToControl;
      this.numberOfJoints = jointsToControl.length;

      jointspaceAccelerationCommand = new JointspaceAccelerationCommand();
      userDesiredJointAccelerations = new DoubleYoVariable[jointsToControl.length];
      weights = new DoubleYoVariable[jointsToControl.length];

      for (int i = 0; i < numberOfJoints; i++)
      {
         String jointName = jointsToControl[i].getName();
         userDesiredJointAccelerations[i] = new DoubleYoVariable(prefix + "_" + jointName + "_qdd_d", registry);
         weights[i] = new DoubleYoVariable(prefix + "_" + jointName + "_weight", registry);
         jointspaceAccelerationCommand.addJoint(jointsToControl[i], Double.NaN);
      }

      abortUserControlMode = new BooleanYoVariable(prefix + "Abort", registry);
   }

   public boolean handleDesiredAccelerationsCommand(DesiredAccelerationCommand<?, ?> command)
   {
      if (!hasWeights.getBooleanValue())
      {
         PrintTools.warn(warningPrefix + "Can not send joint desired accelerations. Do not have all weights set.");
         return false;
      }

      if (command.getNumberOfJoints() != jointsToControl.length)
      {
         PrintTools.warn(warningPrefix + "Unexpected number of joints.");
         return false;
      }

      if (!handleCommandInternal(command))
         return false;

      for (int i = 0; i < numberOfJoints; i++)
         userDesiredJointAccelerations[i].set(command.getDesiredJointAcceleration(i));

      abortUserControlMode.set(false);
      return true;
   }

   @Override
   public void doAction()
   {
      if (getTimeInTrajectory() > TIME_WITH_NO_MESSAGE_BEFORE_ABORT)
      {
         abortUserControlMode.set(true);
         return;
      }

      for (int jointIdx = 0; jointIdx < numberOfJoints; jointIdx++)
      {
         double desiredAcceleration = userDesiredJointAccelerations[jointIdx].getDoubleValue();
         jointspaceAccelerationCommand.setOneDoFJointDesiredAcceleration(jointIdx, desiredAcceleration);
         jointspaceAccelerationCommand.setWeight(jointIdx, weights[jointIdx].getDoubleValue());
      }
   }

   public void setWeights(TObjectDoubleHashMap<String> weights)
   {
      hasWeights.set(true);
      for (int jointIdx = 0; jointIdx < numberOfJoints; jointIdx++)
      {
         OneDoFJoint joint = jointsToControl[jointIdx];
         if (weights.containsKey(joint.getName()))
            this.weights[jointIdx].set(weights.get(joint.getName()));
         else
            hasWeights.set(false);
      }
   }

   public void setWeight(double weight)
   {
      hasWeights.set(true);
      for (int jointIdx = 0; jointIdx < numberOfJoints; jointIdx++)
         this.weights[jointIdx].set(weight);
   }

   @Override
   public void doTransitionIntoAction()
   {
   }

   @Override
   public void doTransitionOutOfAction()
   {
      abortUserControlMode.set(false);
   }

   @Override
   public InverseDynamicsCommand<?> getInverseDynamicsCommand()
   {
      return jointspaceAccelerationCommand;
   }

   @Override
   public FeedbackControlCommand<?> getFeedbackControlCommand()
   {
      return null;
   }

   @Override
   public FeedbackControlCommand<?> createFeedbackControlTemplate()
   {
      return getFeedbackControlCommand();
   }

   @Override
   public boolean abortState()
   {
      return abortUserControlMode.getBooleanValue();
   }

   @Override
   public boolean isEmpty()
   {
      // this control mode does not support command queuing
      return false;
   }

   @Override
   public double getLastTrajectoryPointTime()
   {
      // this control mode does not support command queuing
      return 0.0;
   }

   @Override
   public void clear()
   {

   }

}
