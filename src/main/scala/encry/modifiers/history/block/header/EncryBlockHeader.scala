package encry.modifiers.history.block.header

import com.google.common.primitives.{Ints, _}
import encry.consensus.{Difficulty, DifficultySerializer}
import encry.crypto.PublicKey25519
import encry.modifiers.ModifierWithDigest
import encry.modifiers.history.ADProofs
import encry.modifiers.history.block.payload.EncryBlockPayload
import encry.modifiers.state.box.proof.Signature25519
import encry.settings.{Algos, Constants}
import io.circe.Json
import io.circe.syntax._
import scorex.core.block.Block._
import scorex.core.serialization.Serializer
import scorex.core.{ModifierId, ModifierTypeId}
import scorex.crypto.authds.ADDigest
import scorex.crypto.encode.Base16
import scorex.crypto.hash.Digest32
import scorex.crypto.signatures.{PublicKey, Signature}

import scala.util.Try

case class EncryBlockHeader(override val version: Version,
                            override val accountPubKey: PublicKey25519,
                            override val signature: Signature25519,
                            override val parentId: ModifierId,
                            override val adProofsRoot: Digest32,
                            override val stateRoot: ADDigest, // 32 bytes + 1 (tree height)
                            override val txsRoot: Digest32,
                            override val timestamp: Timestamp,
                            override val height: Int, // TODO: @@ Height
                            var nonce: Long = 0L,
                            difficulty: Difficulty) extends EncryBaseBlockHeader {

  import EncryBlockHeader._

  override type M = EncryBlockHeader

  override val modifierTypeId: ModifierTypeId = EncryBlockHeader.modifierTypeId

  override lazy val id: ModifierId = ModifierId @@ hHash

  val hHash: Digest32 =
    getHash(version, accountPubKey, parentId, adProofsRoot, stateRoot, txsRoot, timestamp, height, nonce, difficulty)

  override val dataToSign: Array[Byte] =
    getMessageToSign(version, accountPubKey, parentId, adProofsRoot, stateRoot, txsRoot, timestamp, height, difficulty)

  lazy val isGenesis: Boolean = height == Constants.Chain.genesisHeight

  lazy val payloadId: ModifierId =
    ModifierWithDigest.computeId(EncryBlockPayload.modifierTypeId, id, txsRoot)

  lazy val adProofsId: ModifierId = ModifierWithDigest.computeId(ADProofs.modifierTypeId, id, adProofsRoot)

  override def serializer: Serializer[M] = EncryBlockHeaderSerializer

  override lazy val json: Json = Map(
    "id" -> Algos.encode(id).asJson,
    "hash" -> Base16.encode(id).asJson,
    "parentId" -> Algos.encode(payloadId).asJson,
    "stateRoot" -> Algos.encode(stateRoot).asJson,
    "txRoot" -> Algos.encode(txsRoot).asJson,
    "timestamp" -> timestamp.asJson,
    "height" -> height.asJson,
    "difficulty" -> difficulty.untag(Difficulty).asJson,
  ).asJson
}

object EncryBlockHeader {

  val modifierTypeId: ModifierTypeId = ModifierTypeId @@ (101: Byte)

  lazy val GenesisParentId: ModifierId = ModifierId @@ Array.fill(Constants.digestLength)(0: Byte)

  def getHash(version: Version,
              accountPubKey: PublicKey25519,
              parentId: ModifierId,
              adProofsRoot: Digest32,
              stateRoot: ADDigest, // 32 bytes + 1 (tree height)
              txsRoot: Digest32,
              timestamp: Timestamp,
              height: Int,
              nonce: Long,
              difficulty: Difficulty): Digest32 = Algos.hash(
    Bytes.concat(
      Array(version),
      accountPubKey.pubKeyBytes,
      parentId,
      adProofsRoot,
      stateRoot,
      txsRoot,
      Longs.toByteArray(timestamp),
      Ints.toByteArray(height),
      Longs.toByteArray(nonce),
      DifficultySerializer.toBytes(difficulty)
    )
  )

  def getMessageToSign(version: Version,
                       accountPubKey: PublicKey25519,
                       parentId: ModifierId,
                       adProofsRoot: Digest32,
                       stateRoot: ADDigest, // 32 bytes + 1 (tree height)
                       txsRoot: Digest32,
                       timestamp: Timestamp,
                       height: Int,
                       difficulty: Difficulty): Array[Byte] = Algos.hash(
    Bytes.concat(
      Array(version),
      accountPubKey.pubKeyBytes,
      parentId,
      adProofsRoot,
      stateRoot,
      txsRoot,
      Longs.toByteArray(timestamp),
      Ints.toByteArray(height),
      DifficultySerializer.toBytes(difficulty)
    )
  )
}

object EncryBlockHeaderSerializer extends Serializer[EncryBlockHeader] {

  override def toBytes(obj: EncryBlockHeader): Array[Byte] =
    Bytes.concat(
      Array(obj.version),
      obj.accountPubKey.pubKeyBytes,
      obj.signature.signature,
      obj.parentId,
      obj.adProofsRoot,
      obj.stateRoot,
      obj.txsRoot,
      Longs.toByteArray(obj.timestamp),
      Ints.toByteArray(obj.height),
      Longs.toByteArray(obj.nonce),
      DifficultySerializer.toBytes(obj.difficulty)
    )


  override def parseBytes(bytes: Array[Byte]): Try[EncryBlockHeader] = Try {
    val version = bytes.head
    val proposition = PublicKey25519(PublicKey @@ bytes.slice(1, 33))
    val signature = Signature25519(Signature @@ bytes.slice(33, 97))
    val parentId = ModifierId @@ bytes.slice(97, 129)
    val adProofsRoot = Digest32 @@ bytes.slice(129, 161)
    val stateRoot =  ADDigest @@ bytes.slice(161, 194)  // 32 bytes + 1 (tree height)
    val txsRoot = Digest32 @@ bytes.slice(194, 226)
    val timestamp = Longs.fromByteArray(bytes.slice(226, 234))
    val height = Ints.fromByteArray(bytes.slice(234, 238))
    val nonce = Longs.fromByteArray(bytes.slice(238, 246))
    val difficulty = DifficultySerializer.parseBytes(bytes.slice(246, 250))

    EncryBlockHeader(
      version, proposition, signature, parentId, adProofsRoot, stateRoot, txsRoot, timestamp, height, nonce, difficulty)
  }
}
