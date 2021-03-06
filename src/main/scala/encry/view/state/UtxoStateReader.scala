package encry.view.state

import encry.modifiers.state.StateModifierDeserializer
import encry.modifiers.state.box._
import encry.view.history.Height
import io.iohk.iodb.Store
import scorex.core.transaction.state.StateReader
import scorex.core.utils.ScorexLogging
import scorex.crypto.authds.ADKey
import scorex.crypto.authds.avltree.batch.{BatchAVLProver, NodeParameters, PersistentBatchAVLProver, VersionedIODBAVLStorage}
import scorex.crypto.hash.{Blake2b256Unsafe, Digest32}

trait UtxoStateReader extends StateReader with ScorexLogging {

  implicit val hf: Blake2b256Unsafe = new Blake2b256Unsafe

  val stateStore: Store

  val height: Height

  private lazy val np = NodeParameters(keySize = EncryBox.BoxIdSize, valueSize = None, labelSize = 32)

  protected lazy val storage = new VersionedIODBAVLStorage(stateStore, np)

  protected lazy val persistentProver: PersistentBatchAVLProver[Digest32, Blake2b256Unsafe] = {
    val bp = new BatchAVLProver[Digest32, Blake2b256Unsafe](keyLength = 32, valueLengthOpt = None)
    PersistentBatchAVLProver.create(bp, storage).get
  }

  def boxById(boxId: ADKey): Option[EncryBaseBox] = persistentProver.unauthenticatedLookup(boxId)
    .map(bytes => StateModifierDeserializer.parseBytes(bytes, boxId.head)).flatMap(_.toOption)

  def boxesByIds(ids: Seq[ADKey]): Seq[EncryBaseBox] = ids.foldLeft(Seq[EncryBaseBox]())((acc, id) =>
    boxById(id).map(bx => acc :+ bx).getOrElse(acc))

  def typedBoxById[B <: EncryBaseBox](boxId: ADKey): Option[EncryBaseBox] =
    boxById(boxId) match {
      case Some(bx: B@unchecked) if bx.isInstanceOf[B] => Some(bx)
      case _ => None
    }

  def getRandomBox: Option[EncryBaseBox] =
    persistentProver.avlProver.randomWalk().map(_._1).flatMap(boxById)
}
