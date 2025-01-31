package scala.build.options

import scala.build.internal.Constants

final case class PackageOptions(
  version: Option[String] = None,
  launcherAppName: Option[String] = None,
  maintainer: Option[String] = None,
  description: Option[String] = None,
  packageTypeOpt: Option[PackageType] = None,
  logoPath: Option[os.Path] = None,
  macOSidentifier: Option[String] = None,
  debianOptions: DebianOptions = DebianOptions(),
  windowsOptions: WindowsOptions = WindowsOptions(),
  redHatOptions: RedHatOptions = RedHatOptions()
) {

  def packageVersion: String =
    version
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse(Constants.version.stripSuffix("-SNAPSHOT"))

}

object PackageOptions {
  implicit val hasHashData: HasHashData[PackageOptions] = HasHashData.derive
  implicit val monoid: ConfigMonoid[PackageOptions]     = ConfigMonoid.derive
}
