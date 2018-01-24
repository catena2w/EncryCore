package encry.cli.commandObjects
import encry.view.history.EncryHistory
import encry.view.mempool.EncryMempool
import encry.view.state.UtxoState
import encry.view.wallet.EncryWallet
import scorex.core.NodeViewHolder

import scala.util.Try

object walletBalance extends Command {

  override def execute(view: NodeViewHolder.CurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool], args: Array[String]): Try[Unit] =
    Try(println(view.vault.currentBalance))

}
