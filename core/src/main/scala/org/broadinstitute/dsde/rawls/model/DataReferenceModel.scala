package org.broadinstitute.dsde.rawls.model

import bio.terra.workspace.model.{DataReferenceDescription, DataReferenceList}
import spray.json.DefaultJsonProtocol._

import scala.collection.JavaConverters._

case class DataRepoSnapshot(name: String, snapshotId: String)
case class DataRepoSnapshotReference(referenceId: String, name: String, workspaceId: String, referenceType: Option[String], reference: Option[String], cloningInstructions: String)
case class DataRepoSnapshotList(snapshots: List[DataRepoSnapshotReference])
case class TerraDataRepoSnapshotRequest(instance: String, snapshot: String)

object DataRepoSnapshotReference {
  def apply(ref: DataReferenceDescription): DataRepoSnapshotReference = {
    DataRepoSnapshotReference(ref.getReferenceId.toString, ref.getName, ref.getWorkspaceId.toString,
      Option(ref.getReferenceType.toString), Option(ref.getReference), ref.getCloningInstructions.toString)
  }
}

object DataRepoSnapshotList {
  def from(ref: DataReferenceList): DataRepoSnapshotList = {
    val buffer = for (r <- ref.getResources.asScala) yield DataRepoSnapshotReference(r)
    DataRepoSnapshotList(buffer.toList)
  }
}

object DataReferenceModelJsonSupport {
  implicit val DataRepoSnapshotFormat = jsonFormat2(DataRepoSnapshot)
  implicit val DataRepoSnapshotReferenceFormat = jsonFormat6(DataRepoSnapshotReference.apply)
  implicit val DataRepoSnapshotListFormat = jsonFormat1(DataRepoSnapshotList.apply)
  implicit val TerraDataRepoSnapshotRequestFormat = jsonFormat2(TerraDataRepoSnapshotRequest)
}
