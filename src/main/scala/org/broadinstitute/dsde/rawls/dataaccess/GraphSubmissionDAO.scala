package org.broadinstitute.dsde.rawls.dataaccess

import com.tinkerpop.blueprints.{Graph, Vertex}
import com.tinkerpop.gremlin.java.GremlinPipeline
import org.joda.time.DateTime
import org.broadinstitute.dsde.rawls.model.{Submission,Workflow}
import scala.collection.JavaConversions._
import scala.language.implicitConversions

/**
 * @author tsharpe
 */

object ExecutionEdgeTypes {
  val submissionEdgeType = "_SubmissionStatus"
  val workflowEdgeType = "_Workflow"
}

class GraphWorkflowDAO extends WorkflowDAO with GraphDAO {

  /** get a workflow by workspace and workflowId */
  override def get(workspaceNamespace: String, workspaceName: String, workflowId: String, txn: RawlsTransaction): Option[Workflow] =
    txn withGraph { db =>
      getVertexProperties(getPipeline(db,workspaceNamespace,workspaceName,workflowId)) map { fromPropertyMap[Workflow](_) }
    }

  /** update a workflow */
  override def update(workspaceNamespace: String, workspaceName: String, workflow: Workflow, txn: RawlsTransaction): Boolean =
    txn withGraph { db =>
      val vertex = getSinglePipelineResult(getPipeline(db,workspaceNamespace,workspaceName,workflow.id))
      if ( vertex.isDefined ) setVertexProperties(workflow, vertex.get)
      vertex.isDefined
    }

  /** delete a workflow */
  override def delete(workspaceNamespace: String, workspaceName: String, workflowId: String, txn: RawlsTransaction): Boolean =
    txn withGraph { db =>
      val vertex = getSinglePipelineResult(getPipeline(db,workspaceNamespace,workspaceName,workflowId))
      if ( vertex.isDefined ) vertex.get.remove
      vertex.isDefined
    }

  private def getPipeline(db: Graph, workspaceNamespace: String, workspaceName: String, workflowId: String) =
    workspacePipeline(db, workspaceNamespace, workspaceName).out(ExecutionEdgeTypes.workflowEdgeType).filter(hasProperty("_id",workflowId))
}

class GraphSubmissionDAO extends SubmissionDAO with GraphDAO {

  /** get a submission by workspace and submissionId */
  override def get(workspaceNamespace: String, workspaceName: String, submissionId: String, txn: RawlsTransaction): Option[Submission] =
    txn withGraph { db =>
      getSinglePipelineResult[Vertex](getPipeline(db,workspaceNamespace,workspaceName,submissionId)) map { fromVertex(workspaceNamespace,workspaceName,_) }
    }

  /** list all submissions in the workspace */
  override def list(workspaceNamespace: String, workspaceName: String, txn: RawlsTransaction): TraversableOnce[Submission] =
    txn withGraph { db =>
      workspacePipeline(db, workspaceNamespace, workspaceName).out(ExecutionEdgeTypes.submissionEdgeType).transform[Submission] { vertex: Vertex => fromVertex(workspaceNamespace,workspaceName,vertex) }.iterator
    }

  /** create a submission (and its workflows) */
  override def save(workspaceNamespace: String, workspaceName: String, submission: Submission, txn: RawlsTransaction) =
    txn withGraph { db =>
      val workspace = getWorkspaceVertex(db, workspaceNamespace, workspaceName).getOrElse(throw new IllegalArgumentException(s"workspace ${workspaceNamespace}/${workspaceName} does not exist"))
      val submissionVertex = setVertexProperties(submission, addVertex(db, null))
      workspace.addEdge(ExecutionEdgeTypes.submissionEdgeType, submissionVertex)
      submission.workflow.foreach { workflow =>
        val workflowVertex = setVertexProperties(workflow, addVertex(db, null))
        workspace.addEdge(ExecutionEdgeTypes.workflowEdgeType, workflowVertex)
        submissionVertex.addEdge(ExecutionEdgeTypes.workflowEdgeType, workflowVertex)
      }
    }

  /** delete a submission (and its workflows) */
  override def delete(workspaceNamespace: String, workspaceName: String, submissionId: String, txn: RawlsTransaction): Boolean =
    txn withGraph { db =>
      val vertex = getSinglePipelineResult(getPipeline(db,workspaceNamespace,workspaceName,submissionId))
      if ( vertex.isDefined ) {
        new GremlinPipeline(vertex.get).out(ExecutionEdgeTypes.workflowEdgeType).remove
        vertex.get.remove
      }
      vertex.isDefined
    }

  private def getPipeline(db: Graph, workspaceNamespace: String, workspaceName: String, submissionId: String) = {
    workspacePipeline(db, workspaceNamespace, workspaceName).out(ExecutionEdgeTypes.submissionEdgeType).filter(hasProperty("_id",submissionId))
  }

  private def fromVertex(workspaceNamespace: String, workspaceName: String, vertex: Vertex): Submission = {
    val propertiesMap = getVertexProperties[Any](new GremlinPipeline(vertex)).get
    val workflows = getPropertiesOfVertices[Any](new GremlinPipeline(vertex).out(ExecutionEdgeTypes.workflowEdgeType)) map { fromPropertyMap[Workflow](_) }
    val fullMap = propertiesMap ++ Map("workspaceNamespace"->workspaceNamespace,"workspaceName"->workspaceName,"workflow"->workflows.toSeq)
    fromPropertyMap[Submission](fullMap)
  }
}
