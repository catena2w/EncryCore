package encry.view.history

import encry.modifiers.EncryPersistentModifier
import encry.modifiers.history.ADProofs
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.{EncryBlockHeader, EncryHeaderChain}
import encry.modifiers.history.block.payload.EncryBlockPayload
import encry.settings.{Algos, NodeSettings}
import encry.view.history.processors.BlockHeaderProcessor
import encry.view.history.processors.payload.BaseBlockPayloadProcessor
import encry.view.history.processors.proofs.BaseADProofProcessor
import scorex.core.consensus.History.{HistoryComparisonResult, ModifierIds}
import scorex.core.consensus.{History, HistoryReader, ModifierSemanticValidity}
import scorex.core.utils.ScorexLogging
import scorex.core.{ModifierId, ModifierTypeId}

import scala.annotation.tailrec
import scala.util.{Failure, Try}

trait EncryHistoryReader
  extends HistoryReader[EncryPersistentModifier, EncrySyncInfo]
    with BlockHeaderProcessor
    with BaseBlockPayloadProcessor
    with BaseADProofProcessor
    with ScorexLogging {

  protected val nodeSettings: NodeSettings

  /**
    * Is there's no history, even genesis block
    */
  def isEmpty: Boolean = bestHeaderIdOpt.isEmpty

  /**
    * Header of best Header chain. Empty if no genesis block is applied yet (from a chain or a PoPoW proof).
    * Transactions and ADProofs for this Header may be missed, to get block from best full chain (in mode that support
    * it) call bestFullBlockOpt.
    */
  def bestHeaderOpt: Option[EncryBlockHeader] = bestHeaderIdOpt.flatMap(typedModifierById[EncryBlockHeader])

  /**
    * Complete block of the best chain with transactions.
    * Always None for an SPV mode, Some(fullBLock) for fullnode regime after initial bootstrap.
    */
  def bestBlockOpt: Option[EncryBlock] =
    bestBlockIdOpt.flatMap(id => typedModifierById[EncryBlockHeader](id)).flatMap(getBlock)

  /**
    * @return ids of count headers starting from offset
    */
  def getHeaderIds(count: Int, offset: Int = 0): Seq[ModifierId] = (offset until (count + offset))
    .flatMap(h => headerIdsAtHeight(h).headOption)

  /**
    * Id of best block to mine
    */
  override def openSurfaceIds(): Seq[ModifierId] = bestBlockIdOpt.orElse(bestHeaderIdOpt).toSeq

  // Compares node`s `SyncInfo` with another`s.
  override def compare(other: EncrySyncInfo): History.HistoryComparisonResult.Value = {
    bestHeaderIdOpt match {
      case Some(id) if other.lastHeaderIds.lastOption.exists(_ sameElements id) =>
        HistoryComparisonResult.Equal
      case Some(id) if other.lastHeaderIds.exists(_ sameElements id) =>
        HistoryComparisonResult.Older
      case Some(_) if other.lastHeaderIds.isEmpty =>
        HistoryComparisonResult.Younger
      case Some(_) =>
        // Compare headers chain
        val ids = other.lastHeaderIds
        ids.view.reverse.find(m => contains(m)) match {
          case Some(_) =>
            HistoryComparisonResult.Younger
          case None => HistoryComparisonResult.Nonsense
        }
      case None =>
        log.warn("Trying to compare with other node while our history is empty")
        HistoryComparisonResult.Older
    }
  }

  /**
    * @param info other's node sync info
    * @param size max return size
    * @return Ids of headerss, that node with info should download and apply to synchronize
    */
  override def continuationIds(info: EncrySyncInfo, size: Int): Option[ModifierIds] = Try {
    if (isEmpty) {
      info.startingPoints
    } else if (info.lastHeaderIds.isEmpty) {
      val heightFrom = Math.min(bestHeaderHeight, size - 1)
      val startId = headerIdsAtHeight(heightFrom).head
      val startHeader = typedModifierById[EncryBlockHeader](startId).get
      val headers = headerChainBack(size, startHeader, _ => false)
        .ensuring(_.headers.exists(_.height == 0), "Should always contain genesis header")
      headers.headers.flatMap(h => Seq((EncryBlockHeader.modifierTypeId, h.id)))
    } else {
      val ids = info.lastHeaderIds
      val lastHeaderInOurBestChain: ModifierId = ids.view.reverse.find(m => isInBestChain(m)).get
      val theirHeight = heightOf(lastHeaderInOurBestChain).get
      val heightFrom = Math.min(bestHeaderHeight, theirHeight + size)
      val startId = headerIdsAtHeight(heightFrom).head
      val startHeader = typedModifierById[EncryBlockHeader](startId).get
      val headerIds = headerChainBack(size, startHeader, h => h.parentId sameElements lastHeaderInOurBestChain)
        .headers.map(h => EncryBlockHeader.modifierTypeId -> h.id)
      headerIds
    }
  }.toOption

  /**
    * @return all possible forks, that contains specified header
    */
  protected[history] def continuationHeaderChains(header: EncryBlockHeader,
                                                  filterCond: EncryBlockHeader => Boolean): Seq[Seq[EncryBlockHeader]] = {
    @tailrec
    def loop(currentHeight: Option[Int], acc: Seq[Seq[EncryBlockHeader]]): Seq[Seq[EncryBlockHeader]] = {
      val nextLevelHeaders = currentHeight.toList
        .flatMap{ h => headerIdsAtHeight(h + 1) }
        .flatMap { id => typedModifierById[EncryBlockHeader](id) }
        .filter(filterCond)
      if (nextLevelHeaders.isEmpty) {
        acc.map(chain => chain.reverse)
      } else {
        val updatedChains = nextLevelHeaders.flatMap { h =>
          acc.find(chain => chain.nonEmpty && (h.parentId sameElements chain.head.id)).map(c => h +: c)
        }
        val nonUpdatedChains = acc.filter(chain => !nextLevelHeaders.exists(_.parentId sameElements chain.head.id))
        loop(currentHeight.map(_ + 1), updatedChains ++ nonUpdatedChains)
      }
    }

    loop(heightOf(header.id), Seq(Seq(header)))
  }

  protected def testApplicable(modifier: EncryPersistentModifier): Try[Unit] = {
    modifier match {
      case header: EncryBlockHeader => validate(header)
      case payload: EncryBlockPayload => validate(payload)
      case adProofs: ADProofs => validate(adProofs)
      case mod: Any => Failure(new Error(s"Modifier $mod is of incorrect type."))
    }
  }

  // Checks whether the modifier is applicable to the history.
  override def applicable(modifier: EncryPersistentModifier): Boolean = testApplicable(modifier).isSuccess

  def lastHeaders(count: Int): EncryHeaderChain = bestHeaderOpt
    .map(bestHeader => headerChainBack(count, bestHeader, _ => false)).getOrElse(EncryHeaderChain.empty)

  override def modifierById(id: ModifierId): Option[EncryPersistentModifier] =
    historyStorage.modifierById(id)
      .ensuring(_.forall(_.id sameElements id), s"Modifier ${Algos.encode(id)} id mismatch")

  def typedModifierById[T <: EncryPersistentModifier](id: ModifierId): Option[T] = modifierById(id) match {
    case Some(m: T@unchecked) if m.isInstanceOf[T] => Some(m)
    case _ => None
  }

  def getBlock(header: EncryBlockHeader): Option[EncryBlock] = {
    val aDProofs = typedModifierById[ADProofs](header.adProofsId)
    typedModifierById[EncryBlockPayload](header.payloadId).map { txs =>
      EncryBlock(header, txs, aDProofs)
    }
  }

  def missedModifiersForFullChain: Seq[(ModifierTypeId, ModifierId)] = {
    if (nodeSettings.verifyTransactions) {
      bestHeaderOpt.toSeq
        .flatMap(h => headerChainBack(bestHeaderHeight + 1, h, _ => false).headers)
        .flatMap(h => Seq((EncryBlockPayload.modifierTypeId, h.payloadId), (ADProofs.modifierTypeId, h.adProofsId)))
        .filter(id => !contains(id._2))
    } else {
      Seq.empty
    }
  }

  /**
    * Return headers, required to apply to reach header2 if you are at header1 position.
    *
    * @param fromHeaderOpt - initial position
    * @param toHeader - header you should reach
    * @return (Modifier required to rollback first, header chain to apply)
    */
  def getChainToHeader(fromHeaderOpt: Option[EncryBlockHeader],
                       toHeader: EncryBlockHeader): (Option[ModifierId], EncryHeaderChain) = {
    fromHeaderOpt match {
      case Some(h1) =>
        val (prevChain, newChain) = commonBlockThenSuffixes(h1, toHeader)
        (prevChain.headOption.map(_.id), newChain.tail)
      case None =>
        (None, headerChainBack(toHeader.height + 1, toHeader, _ => false))
    }
  }

  /**
    * Finds common block and subchains from common block to header1 and header2.
    */
  protected[history] def commonBlockThenSuffixes(header1: EncryBlockHeader,
                                                 header2: EncryBlockHeader): (EncryHeaderChain, EncryHeaderChain) = {
    assert(contains(header1) && contains(header2), "Got non-existing header(s)")
    val heightDelta = Math.max(header1.height - header2.height, 0)

    def loop(numberBack: Int, otherChain: EncryHeaderChain): (EncryHeaderChain, EncryHeaderChain) = {
      val r = commonBlockThenSuffixes(otherChain, header1, numberBack + heightDelta)
      if (r._1.head == r._2.head) {
        r
      } else {
        val biggerOther = headerChainBack(numberBack, otherChain.head, _ => false) ++ otherChain.tail
        if (!otherChain.head.isGenesis) {
          loop(biggerOther.size, biggerOther)
        } else {
          throw new Error(s"Common point not found for headers $header1 and $header2")
        }
      }
    }

    loop(2, EncryHeaderChain(Seq(header2)))
  }

  protected[history] def commonBlockThenSuffixes(otherChain: EncryHeaderChain,
                                                 startHeader: EncryBlockHeader,
                                                 limit: Int): (EncryHeaderChain, EncryHeaderChain) = {
    def until(h: EncryBlockHeader): Boolean = otherChain.exists(_.id sameElements h.id)

    val ourChain = headerChainBack(limit, startHeader, until)
    val commonBlock = ourChain.head
    val commonBlockThenSuffixes = otherChain.takeAfter(commonBlock)
    (ourChain, commonBlockThenSuffixes)
  }

  override def syncInfo: EncrySyncInfo = if (isEmpty) {
    EncrySyncInfo(Seq.empty)
  } else {
    EncrySyncInfo(lastHeaders(EncrySyncInfo.MaxBlockIds).headers.map(_.id))
  }

  override def isSemanticallyValid(modifierId: ModifierId): ModifierSemanticValidity.Value = {
    historyStorage.store.get(validityKey(modifierId)) match {
      case Some(b) if b.data.headOption.contains(1.toByte) => ModifierSemanticValidity.Valid
      case Some(b) if b.data.headOption.contains(0.toByte) => ModifierSemanticValidity.Invalid
      case None if contains(modifierId) => ModifierSemanticValidity.Unknown
      case None => ModifierSemanticValidity.Absent
      case m =>
        log.error(s"Incorrect validity status: $m")
        ModifierSemanticValidity.Absent
    }
  }
}
