package us.ihmc.commonWalkingControlModules.momentumBasedController;

import org.ejml.data.DenseMatrix64F;
import org.ejml.data.RowD1Matrix64F;
import org.ejml.ops.CommonOps;
import us.ihmc.utilities.CheckTools;
import us.ihmc.utilities.math.NullspaceCalculator;
import us.ihmc.utilities.screwTheory.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * @author twan
 *         Date: 4/30/13
 */
public class MotionConstraintHandler
{
   private final InverseDynamicsJoint[] jointsInOrder;
   private final LinkedHashMap<InverseDynamicsJoint, int[]> columnsForJoints = new LinkedHashMap<InverseDynamicsJoint, int[]>();
   private final int nDegreesOfFreedom;
   private final NullspaceCalculator nullspaceCalculator = new NullspaceCalculator(SpatialMotionVector.SIZE, true);

   private final SpatialAccelerationVector convectiveTerm = new SpatialAccelerationVector();
   private final DenseMatrix64F convectiveTermMatrix = new DenseMatrix64F(SpatialAccelerationVector.SIZE, 1);

   private final DenseMatrix64F taskSpaceAccelerationMatrix = new DenseMatrix64F(SpatialAccelerationVector.SIZE, 1);
   private final List<DenseMatrix64F> jList = new ArrayList<DenseMatrix64F>();
   private final List<DenseMatrix64F> pList = new ArrayList<DenseMatrix64F>();
   private final List<DenseMatrix64F> nList = new ArrayList<DenseMatrix64F>();

   private final List<DenseMatrix64F> zList = new ArrayList<DenseMatrix64F>();
   private final DenseMatrix64F j = new DenseMatrix64F(1, 1);
   private final DenseMatrix64F p = new DenseMatrix64F(1, 1);
   private final DenseMatrix64F z = new DenseMatrix64F(1, 1);
   private final DenseMatrix64F n = new DenseMatrix64F(1, 1);

   private final DenseMatrix64F jBlockCompact = new DenseMatrix64F(1, 1);
   private final DenseMatrix64F sJ = new DenseMatrix64F(1, 1);
   private final DenseMatrix64F nCompactBlock = new DenseMatrix64F(1, 1);

   private int motionConstraintIndex = 0;
   private int nullspaceIndex = 0;

   public MotionConstraintHandler(InverseDynamicsJoint[] jointsInOrder)
   {
      this.jointsInOrder = jointsInOrder;
      this.nDegreesOfFreedom = ScrewTools.computeDegreesOfFreedom(jointsInOrder);
      for (InverseDynamicsJoint joint : jointsInOrder)
      {
         columnsForJoints.put(joint, ScrewTools.computeIndicesForJoint(jointsInOrder, joint));
      }
   }

   public void reset()
   {
      motionConstraintIndex = 0;
      nullspaceIndex = 0;
   }

   public void setDesiredSpatialAcceleration(InverseDynamicsJoint[] constrainedJoints, GeometricJacobian jacobian, TaskspaceConstraintData
         taskspaceConstraintData)
   {
      // (S * J) * vdot = S * (Tdot - Jdot * v)

      SpatialAccelerationVector taskSpaceAcceleration = taskspaceConstraintData.getSpatialAcceleration();
      DenseMatrix64F selectionMatrix = taskspaceConstraintData.getSelectionMatrix();
      DenseMatrix64F nullspaceMultipliers = taskspaceConstraintData.getNullspaceMultipliers();

      RigidBody base = getBase(taskSpaceAcceleration);
      RigidBody endEffector = getEndEffector(taskSpaceAcceleration);

      // TODO: inefficient
      GeometricJacobian baseToEndEffectorJacobian = new GeometricJacobian(base, endEffector, taskSpaceAcceleration.getExpressedInFrame());    // FIXME: garbage, repeated computation
      baseToEndEffectorJacobian.compute();

      // TODO: inefficient
      DesiredJointAccelerationCalculator desiredJointAccelerationCalculator = new DesiredJointAccelerationCalculator(baseToEndEffectorJacobian, null);    // TODO: garbage
      desiredJointAccelerationCalculator.computeJacobianDerivativeTerm(convectiveTerm);
      convectiveTerm.getBodyFrame().checkReferenceFrameMatch(taskSpaceAcceleration.getBodyFrame());
      convectiveTerm.getExpressedInFrame().checkReferenceFrameMatch(taskSpaceAcceleration.getExpressedInFrame());
      convectiveTerm.packMatrix(convectiveTermMatrix, 0);

      jBlockCompact.reshape(selectionMatrix.getNumRows(), baseToEndEffectorJacobian.getNumberOfColumns());
      CommonOps.mult(selectionMatrix, baseToEndEffectorJacobian.getJacobianMatrix(), jBlockCompact);

      DenseMatrix64F jFullBlock = getMatrixFromList(jList, motionConstraintIndex, jBlockCompact.getNumRows(), nDegreesOfFreedom);
      compactBlockToFullBlock(baseToEndEffectorJacobian.getJointsInOrder(), jBlockCompact, jFullBlock);

      DenseMatrix64F pBlock = getMatrixFromList(pList, motionConstraintIndex, selectionMatrix.getNumRows(), 1);
      taskSpaceAcceleration.packMatrix(taskSpaceAccelerationMatrix, 0);
      CommonOps.mult(selectionMatrix, taskSpaceAccelerationMatrix, pBlock);
      CommonOps.multAdd(-1.0, selectionMatrix, convectiveTermMatrix, pBlock);

      motionConstraintIndex++;

      int nullity = nullspaceMultipliers.getNumRows();
      if (nullity > 0)
      {
         sJ.reshape(selectionMatrix.getNumRows(), jacobian.getJacobianMatrix().getNumCols());
         CommonOps.mult(selectionMatrix, jacobian.getJacobianMatrix(), sJ);
         nullspaceCalculator.setMatrix(sJ, nullity);
         DenseMatrix64F nullspace = nullspaceCalculator.getNullspace();
         nCompactBlock.reshape(nullspace.getNumCols(), nullspace.getNumRows());
         CommonOps.transpose(nullspace, nCompactBlock);
         DenseMatrix64F nFullBLock = getMatrixFromList(nList, nullspaceIndex, nullity, nDegreesOfFreedom);
         compactBlockToFullBlock(jacobian.getJointsInOrder(), nCompactBlock, nFullBLock);
         nullspaceIndex++;
      }
   }

   private void compactBlockToFullBlock(InverseDynamicsJoint[] joints, DenseMatrix64F compactMatrix, DenseMatrix64F fullBlock)
   {
      for (InverseDynamicsJoint joint : joints)
      {
         int[] indicesIntoCompactBlock = ScrewTools.computeIndicesForJoint(joints, joint);
         int[] indicesIntoFullBlock = columnsForJoints.get(joint);

         for (int i = 0; i < indicesIntoCompactBlock.length; i++)
         {
            int compactBlockIndex = indicesIntoCompactBlock[i];
            int fullBlockIndex = indicesIntoFullBlock[i];
            CommonOps.extract(compactMatrix, 0, compactMatrix.getNumRows(), compactBlockIndex, compactBlockIndex + 1, fullBlock, 0, fullBlockIndex);
         }
      }
   }

   public void setDesiredJointAcceleration(InverseDynamicsJoint joint, DenseMatrix64F jointAcceleration)
   {
      CheckTools.checkEquals(joint.getDegreesOfFreedom(), jointAcceleration.getNumRows());
      int[] columnsForJoint = this.columnsForJoints.get(joint);


      DenseMatrix64F JpBlock = getMatrixFromList(jList, motionConstraintIndex, joint.getDegreesOfFreedom(), nDegreesOfFreedom);
      new DenseMatrix64F(joint.getDegreesOfFreedom(), nDegreesOfFreedom);
      for (int i = 0; i < joint.getDegreesOfFreedom(); i++)
      {
         JpBlock.set(i, columnsForJoint[i], 1.0);
      }

      DenseMatrix64F ppBlock = getMatrixFromList(pList, motionConstraintIndex, joint.getDegreesOfFreedom(), 1);
      ppBlock.set(jointAcceleration);

      motionConstraintIndex++;
   }

   public void compute()
   {
      assembleEquation(jList, pList, motionConstraintIndex, j, p);
      assembleEquation(nList, zList, nullspaceIndex, n, z);
   }

   private void assembleEquation(List<DenseMatrix64F> matrixList, List<DenseMatrix64F> vectorList, int size, DenseMatrix64F matrix, DenseMatrix64F vector)
   {
      if (matrixList.size() != vectorList.size())
         throw new RuntimeException("sizes not equal");

      int nRows = 0;
      for (int i = 0; i < size; i++)
      {
         nRows += matrixList.get(i).getNumRows();
      }
      matrix.reshape(nRows, nDegreesOfFreedom);
      vector.reshape(nRows, 1);

      int rowNumber = 0;
      for (int i = 0; i < size; i++)
      {
         DenseMatrix64F JpBlock = matrixList.get(i);
         CommonOps.insert(JpBlock, matrix, rowNumber, 0);

         DenseMatrix64F ppBlock = vectorList.get(i);
         CommonOps.insert(ppBlock, vector, rowNumber, 0);

         rowNumber += JpBlock.getNumRows();
      }
   }

   public DenseMatrix64F getJacobian()
   {
      return j;
   }

   public DenseMatrix64F getRightHandSide()
   {
      return p;
   }

   public DenseMatrix64F getNullspaceTransposeMatrix()
   {
      return n;
   }

   public DenseMatrix64F getNullspaceMultipliers()
   {
      return z;
   }

   private static DenseMatrix64F getMatrixFromList(List<DenseMatrix64F> matrixList, int index, int nRows, int nColumns)
   {
      for (int i = matrixList.size(); i <= index; i++)
      {
         matrixList.add(new DenseMatrix64F(1, 1));
      }
      DenseMatrix64F ret = matrixList.get(index);
      ret.reshape(nRows, nColumns);
      return ret;
   }

   private RigidBody getBase(SpatialAccelerationVector taskSpaceAcceleration)
   {
      for (InverseDynamicsJoint joint : jointsInOrder)
      {
         RigidBody predecessor = joint.getPredecessor();
         if (predecessor.getBodyFixedFrame() == taskSpaceAcceleration.getBaseFrame())
            return predecessor;
      }

      throw new RuntimeException("Base for " + taskSpaceAcceleration + " could not be determined");
   }

   private RigidBody getEndEffector(SpatialAccelerationVector taskSpaceAcceleration)
   {
      for (InverseDynamicsJoint joint : jointsInOrder)
      {
         RigidBody successor = joint.getSuccessor();
         if (successor.getBodyFixedFrame() == taskSpaceAcceleration.getBodyFrame())
            return successor;
      }

      throw new RuntimeException("End effector for " + taskSpaceAcceleration + " could not be determined");
   }
}
