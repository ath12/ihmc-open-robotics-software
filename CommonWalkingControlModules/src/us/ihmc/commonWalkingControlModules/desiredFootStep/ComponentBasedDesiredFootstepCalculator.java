package us.ihmc.commonWalkingControlModules.desiredFootStep;

import java.util.List;

import javax.media.j3d.Transform3D;
import javax.vecmath.Matrix3d;
import javax.vecmath.Vector3d;

import us.ihmc.commonWalkingControlModules.bipedSupportPolygons.ContactablePlaneBody;
import us.ihmc.commonWalkingControlModules.desiredHeadingAndVelocity.DesiredHeadingControlModule;
import us.ihmc.commonWalkingControlModules.desiredHeadingAndVelocity.DesiredVelocityControlModule;
import us.ihmc.graphics3DAdapter.GroundProfile;
import us.ihmc.robotSide.RobotSide;
import us.ihmc.robotSide.SideDependentList;
import us.ihmc.utilities.math.MathTools;
import us.ihmc.utilities.math.geometry.FrameOrientation;
import us.ihmc.utilities.math.geometry.FramePoint;
import us.ihmc.utilities.math.geometry.FramePose;
import us.ihmc.utilities.math.geometry.FrameVector;
import us.ihmc.utilities.math.geometry.FrameVector2d;
import us.ihmc.utilities.math.geometry.PoseReferenceFrame;
import us.ihmc.utilities.math.geometry.ReferenceFrame;
import us.ihmc.utilities.math.geometry.RotationFunctions;

import com.yobotics.simulationconstructionset.DoubleYoVariable;
import com.yobotics.simulationconstructionset.YoVariableRegistry;

public class ComponentBasedDesiredFootstepCalculator extends AbstractAdjustableDesiredFootstepCalculator
{
   private final DoubleYoVariable inPlaceWidth = new DoubleYoVariable("inPlaceWidth", registry);
   private final DoubleYoVariable maxStepLength = new DoubleYoVariable("maxStepLength", registry);

   private final DoubleYoVariable minStepWidth = new DoubleYoVariable("minStepWidth", registry);
   private final DoubleYoVariable maxStepWidth = new DoubleYoVariable("maxStepWidth", registry);

   private final DoubleYoVariable stepPitch = new DoubleYoVariable("stepPitch", registry);

   private final DoubleYoVariable velocityMagnitudeInHeading = new DoubleYoVariable("velocityMagnitudeInHeading", registry);
   private final DoubleYoVariable velocityMagnitudeToLeftOfHeading = new DoubleYoVariable("velocityMagnitudeToLeftOfHeading", registry);

   private SideDependentList<? extends ReferenceFrame> ankleZUpFrames;

   private final DesiredHeadingControlModule desiredHeadingControlModule;
   private final DesiredVelocityControlModule desiredVelocityControlModule;

   private GroundProfile groundProfile;

   public ComponentBasedDesiredFootstepCalculator(SideDependentList<? extends ReferenceFrame> ankleZUpFrames,
           SideDependentList<? extends ContactablePlaneBody> bipedFeet, DesiredHeadingControlModule desiredHeadingControlModule,
           DesiredVelocityControlModule desiredVelocityControlModule, YoVariableRegistry parentRegistry)
   {
      super(bipedFeet, getFramesToStoreFootstepsIn(), parentRegistry);

      this.ankleZUpFrames = ankleZUpFrames;

      this.desiredHeadingControlModule = desiredHeadingControlModule;
      this.desiredVelocityControlModule = desiredVelocityControlModule;
   }

   public void setGroundProfile(GroundProfile groundProfile)
   {
      this.groundProfile = groundProfile;
   }

   public void initializeDesiredFootstep(RobotSide supportLegSide)
   {
      RobotSide swingLegSide = supportLegSide.getOppositeSide();
      ReferenceFrame supportAnkleZUpFrame = ankleZUpFrames.get(supportLegSide);
      ReferenceFrame desiredHeadingFrame = desiredHeadingControlModule.getDesiredHeadingFrame();
      Matrix3d footToWorldRotation = computeDesiredFootRotation(desiredHeadingFrame);

      FramePoint footstepPosition = getDesiredFootstepPosition(supportAnkleZUpFrame, swingLegSide, desiredHeadingFrame, footToWorldRotation);
      
      setYoVariables(swingLegSide, footToWorldRotation, footstepPosition.getVectorCopy());
   }
   
   @Override
   public Footstep predictFootstepAfterDesiredFootstep(RobotSide supportLegSide, Footstep desiredFootstep)
   {
      RobotSide futureSwingLegSide = supportLegSide;
      ReferenceFrame futureSupportAnkleZUpFrame = desiredFootstep.getPoseReferenceFrame();
      ReferenceFrame desiredHeadingFrame = desiredHeadingControlModule.getDesiredHeadingFrame();
      Matrix3d footToWorldRotation = computeDesiredFootRotation(desiredHeadingFrame);
      
      FramePoint footstepPosition = getDesiredFootstepPosition(futureSupportAnkleZUpFrame, futureSwingLegSide, desiredHeadingFrame, footToWorldRotation);
      FrameOrientation footstepOrientation = new FrameOrientation(ReferenceFrame.getWorldFrame());
      double[] yawPitchRoll = new double[3];
      RotationFunctions.getYawPitchRoll(yawPitchRoll, footToWorldRotation);
      footstepOrientation.setYawPitchRoll(yawPitchRoll);

      FramePose footstepPose = new FramePose(footstepPosition, footstepOrientation);
      footstepPose.changeFrame(ReferenceFrame.getWorldFrame());
      PoseReferenceFrame poseReferenceFrame = new PoseReferenceFrame("poseReferenceFrame", footstepPose);
      
      ContactablePlaneBody foot = contactableBodies.get(futureSwingLegSide);
      boolean trustHeight = true;
      ReferenceFrame soleFrame = FootstepUtils.createSoleFrame(poseReferenceFrame, foot);
      List<FramePoint> contactPoints = FootstepUtils.getContactPointsInFrame(getContactPoints(futureSwingLegSide), soleFrame);
      
      return new Footstep(foot, poseReferenceFrame, soleFrame, contactPoints, trustHeight);
   }
   
   private FramePoint getDesiredFootstepPosition(ReferenceFrame supportAnkleZUpFrame, RobotSide swingLegSide, ReferenceFrame desiredHeadingFrame,
         Matrix3d footToWorldRotation)
   {
      FrameVector2d desiredOffsetFromAnkle = computeDesiredOffsetFromSupportAnkle(swingLegSide, desiredHeadingFrame);
      FramePoint footstepPosition = computeDesiredFootPosition(swingLegSide, supportAnkleZUpFrame, desiredOffsetFromAnkle, footToWorldRotation);
      footstepPosition.changeFrame(ReferenceFrame.getWorldFrame());
      return footstepPosition;
   }
   
   // TODO: clean up
   private FrameVector2d computeDesiredOffsetFromSupportAnkle(RobotSide swingLegSide, ReferenceFrame desiredHeadingFrame)
   {
      FrameVector2d desiredHeading = desiredHeadingControlModule.getDesiredHeading();
      FrameVector2d desiredVelocity = desiredVelocityControlModule.getDesiredVelocity();
      FrameVector2d toLeftOfDesiredHeading = new FrameVector2d(desiredHeading.getReferenceFrame(), -desiredHeading.getY(), desiredHeading.getX());

      desiredVelocity.changeFrame(desiredHeading.getReferenceFrame());
      velocityMagnitudeInHeading.set(desiredVelocity.dot(desiredHeading));
      velocityMagnitudeToLeftOfHeading.set(desiredVelocity.dot(toLeftOfDesiredHeading));

//    double stepForward = maxStepLength.getDoubleValue() * velocityMagnitudeInHeading.getDoubleValue();
//    double stepSideways = swingLegSide.negateIfRightSide(inPlaceWidth.getDoubleValue());    // maxStepLength.getDoubleValue() * velocityMagnitudeToLeftOfHeading;

//    FrameVector2d desiredVelocityInSupportAnkleZUpFrame = desiredVelocity.changeFrameCopy(supportAnkleZUpFrame);
      FrameVector2d desiredVelocityInHeadingFrame = desiredVelocity.changeFrameCopy(desiredHeadingFrame);

      FrameVector2d desiredOffsetFromAnkle = new FrameVector2d(desiredHeadingFrame, 0.0, swingLegSide.negateIfRightSide(inPlaceWidth.getDoubleValue()));    // desiredVelocityInHeadingFrame);
      desiredOffsetFromAnkle.add(desiredVelocityInHeadingFrame);

      if (desiredOffsetFromAnkle.getX() > maxStepLength.getDoubleValue())
         desiredOffsetFromAnkle.setX(maxStepLength.getDoubleValue());

      if (swingLegSide == RobotSide.LEFT)
      {
         desiredOffsetFromAnkle.setY(MathTools.clipToMinMax(desiredOffsetFromAnkle.getY(), minStepWidth.getDoubleValue(), maxStepWidth.getDoubleValue()));
      }
      else
      {
         desiredOffsetFromAnkle.setY(MathTools.clipToMinMax(desiredOffsetFromAnkle.getY(), -maxStepWidth.getDoubleValue(), -minStepWidth.getDoubleValue()));
      }

      return desiredOffsetFromAnkle;
   }

   private Matrix3d computeDesiredFootRotation(ReferenceFrame desiredHeadingFrame)
   {
      Transform3D footToSupportTransform = desiredHeadingFrame.getTransformToDesiredFrame(ReferenceFrame.getWorldFrame());
      Matrix3d footToSupportRotation = new Matrix3d();
      footToSupportTransform.get(footToSupportRotation);
      double yaw = RotationFunctions.getYaw(footToSupportRotation);
      double pitch = stepPitch.getDoubleValue();
      double roll = 0.0;
      RotationFunctions.setYawPitchRoll(footToSupportRotation, yaw, pitch, roll);

      return footToSupportRotation;
   }


   private FramePoint computeDesiredFootPosition(RobotSide upcomingSwingLegSide, ReferenceFrame upcomingSupportAnkleZUpFrame,
           FrameVector2d desiredOffsetFromAnkle, Matrix3d swingFootToWorldRotation)
   {
      ContactablePlaneBody upcomingSwingFoot = contactableBodies.get(upcomingSwingLegSide);
      ReferenceFrame worldFrame = ReferenceFrame.getWorldFrame();

      desiredOffsetFromAnkle.changeFrame(upcomingSupportAnkleZUpFrame);
      FramePoint footstepPosition = new FramePoint(upcomingSupportAnkleZUpFrame, desiredOffsetFromAnkle.getX(), desiredOffsetFromAnkle.getY(), 0.0);
      footstepPosition.changeFrame(worldFrame);

      double footstepMinZ = DesiredFootstepCalculatorTools.computeMinZPointWithRespectToAnkleInWorldFrame(swingFootToWorldRotation, upcomingSwingFoot);

      if (groundProfile == null)
      {
         /*
          * Assume that the ground height is constant.
          *
          * Specifically, if we assume that:
          * 1) the lowest contact point on the upcoming swing foot is in contact with the ground
          * 2) the ground height at the lowest upcoming swing foot contact point is the same as the ground height at the
          *    lowest swing foot contact point
          *
          * then the following holds:
          *
          * let upcomingSwingMinZ be the z coordinate of the vector (expressed in world frame) from upcoming swing ankle to the lowest contact point on
          * the stance foot (compared in world frame). Current foot orientation is used to determine this value.
          *
          * let footstepMinZ be the z coordinate of the vector (expressed in world frame) from planned swing ankle to the lowest contact point on
          * the planned swing foot (compared in world frame). Planned foot orientation is used to determine this value
          *
          * let zUpcomingSwing be the z coordinate of the upcoming swing ankle, expressed in world frame.
          * let zFootstep be the z coordinate of the footstep, expressed in world frame (this is what we're after)
          * let zGround be the z coordinate of the lowest point on the stance foot i.e. of the ground
          * zUpcomingSwing = zGround - upcomingSwingMinZ
          * zFootstep      = zGround - footstepMinZ
          *                = zUpcomingSwing + upcomingSwingMinZ - footstepMinZ
          */

         FramePoint upcomingSwingAnkle = new FramePoint(upcomingSwingFoot.getBodyFrame());
         upcomingSwingAnkle.changeFrame(worldFrame);
         double zUpcomingSwing = upcomingSwingAnkle.getZ();

         FrameVector searchDirection = new FrameVector(upcomingSupportAnkleZUpFrame, 0.0, 0.0, -1.0);
         FramePoint upcomingSwingMinZPoint = DesiredFootstepCalculatorTools.computeMaximumPointsInDirection(upcomingSwingFoot.getContactPoints(),
                                                searchDirection, 1).get(0);
         upcomingSwingMinZPoint.changeFrame(ankleZUpFrames.get(upcomingSwingLegSide));
         double upcomingSwingMinZ = upcomingSwingMinZPoint.getZ();

         double zFootstep = zUpcomingSwing + upcomingSwingMinZ - footstepMinZ;
         footstepPosition.setZ(zFootstep);
      }
      else
      {
         /*
          * use ground profile to determine height at the planned foot position
          */

         footstepPosition.changeFrame(ReferenceFrame.getWorldFrame());
         double groundZ = groundProfile.heightAt(footstepPosition.getX(), footstepPosition.getY(), 0.0);
         double ankleHeight = FootstepUtils.getSoleToAnkleHeight(upcomingSwingFoot);
         double ankleZ = groundZ + ankleHeight;

         footstepPosition.setZ(ankleZ);
      }



      return footstepPosition;
   }

   private void setYoVariables(RobotSide swingLegSide, Matrix3d rotation, Vector3d translation)
   {
      footstepOrientations.get(swingLegSide).set(rotation);
      footstepPositions.get(swingLegSide).set(translation);
   }

   public void setInPlaceWidth(double inPlaceWidth)
   {
      this.inPlaceWidth.set(inPlaceWidth);
   }

   public void setMaxStepLength(double maxStepLength)
   {
      this.maxStepLength.set(maxStepLength);
   }

   public void setMinStepWidth(double minStepWidth)
   {
      this.minStepWidth.set(minStepWidth);
   }

   public void setMaxStepWidth(double maxStepWidth)
   {
      this.maxStepWidth.set(maxStepWidth);
   }

   public void setStepPitch(double stepPitch)
   {
      this.stepPitch.set(stepPitch);
   }

   private static SideDependentList<ReferenceFrame> getFramesToStoreFootstepsIn()
   {
      return new SideDependentList<ReferenceFrame>(ReferenceFrame.getWorldFrame(), ReferenceFrame.getWorldFrame());
   }

   protected List<FramePoint> getContactPoints(RobotSide swingSide)
   {
      double stepPitch = this.stepPitch.getDoubleValue();
      List<FramePoint> allContactPoints = contactableBodies.get(swingSide).getContactPoints();
      if (stepPitch == 0.0)
      {
         return allContactPoints;
      }
      else
      {
         FrameVector forwardInFootFrame = new FrameVector(contactableBodies.get(swingSide).getBodyFrame());
         ReferenceFrame frame = allContactPoints.get(0).getReferenceFrame();
         forwardInFootFrame.changeFrame(frame);
         forwardInFootFrame.scale(Math.signum(stepPitch));
         int nPoints = 2;

         return DesiredFootstepCalculatorTools.computeMaximumPointsInDirection(allContactPoints, forwardInFootFrame, nPoints);
      }
   }
}
