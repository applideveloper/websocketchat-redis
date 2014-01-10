import play.api.GlobalSettings
import play.api.Application
import play.api.Logger

object Global extends GlobalSettings {

  override def onStop(app: Application) {
    Logger.debug("!!! Global#onStop2 !!!")
  }

}
