sys.props.get("plugin.version") match {
  case Some(v) => addSbtPlugin("com.alexdupre" % "sbt-klaviyo4s" % v)
  case None    => sys.error("The system property 'plugin.version' is not defined")
}
