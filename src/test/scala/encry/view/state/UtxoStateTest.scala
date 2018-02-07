package encry.view.state

import java.io.File

import akka.actor.ActorRef
import encry.account.Address
import encry.local.TestHelper
import encry.modifiers.mempool.PaymentTransaction
import encry.settings.Constants
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.core.ModifierId
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.core.utils.NetworkTime
import scorex.crypto.authds.avltree.batch._
import scorex.crypto.authds.{ADKey, ADValue}
import scorex.crypto.hash.{Blake2b256Unsafe, Digest32}
import scorex.utils.Random

class UtxoStateTest extends org.scalatest.FunSuite {

  test("FilterValid(txs) should return only valid txs (against current state).") {

    val dir: File = new File(s"${System.getProperty("user.dir")}/test-data/state1")
    dir.mkdir()

    assert(dir.exists() && dir.isDirectory && dir.listFiles.isEmpty, "dir is invalid.")

    def utxoFromBoxHolder(bh: BoxHolder, dir: File, nodeViewHolderRef: Option[ActorRef]): UtxoState = {
      val p = new BatchAVLProver[Digest32, Blake2b256Unsafe](keyLength = 32, valueLengthOpt = None)
      bh.sortedBoxes.foreach(b => p.performOneOperation(Insert(b.id, ADValue @@ b.bytes)).ensuring(_.isSuccess))

      val stateStore = new LSMStore(dir, keySize = 32, keepVersions = 0)
      val indexStore = new LSMStore(dir, keySize = 32, keepVersions = 0)

      new UtxoState(EncryState.genesisStateVersion, stateStore, indexStore, None) {
        override protected lazy val persistentProver: PersistentBatchAVLProver[Digest32, Blake2b256Unsafe] =
          PersistentBatchAVLProver.create(
            p, storage, paranoidChecks = true
          ).get
      }
    }

    val bh = BoxHolder(TestHelper.genAssetBoxes)

    val state = utxoFromBoxHolder(bh, dir, None)

    val factory = TestHelper
    val keys = factory.getOrGenerateKeys(factory.Props.keysFilePath)

    val validTxs = keys.map { key =>
      val proposition = key.publicImage
      val fee = factory.Props.txFee
      val timestamp = 1234567L
      val useBoxes = IndexedSeq(factory.genAssetBox(Address @@ key.publicImage.address)).map(_.id)
      val outputs = IndexedSeq((Address @@ factory.Props.recipientAddr, factory.Props.boxValue))
      val sig = PrivateKey25519Companion.sign(
        key,
        PaymentTransaction.getMessageToSign(proposition, fee, timestamp, useBoxes, outputs)
      )
      PaymentTransaction(proposition, fee, timestamp, sig, useBoxes, outputs)
    }

    val invalidTxs = keys.map { key =>
      val proposition = key.publicImage
      val fee = factory.Props.txFee
      val timestamp = 123456789L
      val useBoxes =
        IndexedSeq(factory.genAssetBox(Address @@ "3goCpFrrBakKJwxk7d4oY5HN54dYMQZbmVWKvQBPZPDvbL3hHp")).map(_.id)
      val outputs = IndexedSeq((Address @@ factory.Props.recipientAddr, 30000L))
      val sig = PrivateKey25519Companion.sign(
        key,
        PaymentTransaction.getMessageToSign(proposition, fee, timestamp, useBoxes, outputs)
      )
      PaymentTransaction(proposition, fee, timestamp, sig, useBoxes, outputs)
    }

    val filteredValidTxs = state.filterValid(validTxs)

    assert(filteredValidTxs.size == validTxs.size, s"filterValid(validTxs) " +
      s"return ${filteredValidTxs.size}, but ${validTxs.size} was expected.")

    val filteredInvalidTxs = state.filterValid(invalidTxs)

    assert(filteredInvalidTxs.isEmpty, s"filterValid(invalidTxs) " +
      s"return ${filteredInvalidTxs.size}, but 0 was expected.")

    val filteredValidAndInvalidTxs = state.filterValid(validTxs ++ invalidTxs)

    assert(filteredValidAndInvalidTxs.size == validTxs.size, s"filterValid(validTxs + invalidTxs) " +
      s"return ${filteredValidAndInvalidTxs.size}, but ${validTxs.size} was expected.")
  }

  test("BatchAVLProver should have the same digest after rollback as before.") {

    val dir: File = new File(s"${System.getProperty("user.dir")}/test-data/state2")
    dir.mkdir()

    assert(dir.exists() && dir.isDirectory && dir.listFiles.isEmpty, "dir is invalid.")

    val store = new LSMStore(dir, keySize = 32, keepVersions = Constants.keepVersions)

    implicit val hf: Blake2b256Unsafe = new Blake2b256Unsafe

    val prover: BatchAVLProver[Digest32, Blake2b256Unsafe] =
      new BatchAVLProver[Digest32, Blake2b256Unsafe](keyLength = 32, valueLengthOpt = None)

    val np = NodeParameters(keySize = 32, labelSize = 32)

    val storage: VersionedAVLStorage[Digest32] = new VersionedIODBAVLStorage(store, np)

    val persistentProver: PersistentBatchAVLProver[Digest32, Blake2b256Unsafe] =
      PersistentBatchAVLProver.create(prover, storage).get

    val valuesToInsert = (1 until 100).map { i =>
      Array.fill(32)(i.toByte) -> Array.fill(64)(i.toByte)
    }

    val initialDigest = persistentProver.digest

    valuesToInsert.foreach(v => prover.performOneOperation(Insert(ADKey @@ v._1, ADValue @@ v._2))
      .ensuring(_.isSuccess))

    persistentProver.rollback(initialDigest)

    val afterRollbackDigest = persistentProver.digest

    assert(afterRollbackDigest sameElements initialDigest, "Invalid digest after rollback.")

    store.close()
  }
}
