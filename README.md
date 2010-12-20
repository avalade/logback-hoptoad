# HoptoadAppender for LOGBack
This HoptoadAppender is for use with LOGBack (http://logback.qos.ch) to log errors and warnings
to the Hoptoad (http://hoptoadapp.com) application.  It uses the excellent Databinder Dispatch
library for doing asynchronous HTTP calls to the Hoptoad application.

## Configuration
It's recommended that you configure the HoptoadAppender with a Filter that will limit logging messages
to the ERROR level only.

    <configuration>
      <appender name="HOPTOAD" class="com.gs.logging.HoptoadAppender">
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
          <level>ERROR</level>
        </filter>
        <apiKey><!-- INSERT HOPTOAD API KEY HERE --></apiKey>
        <secure>false</secure> <!-- Change to true if you are using HTTPS for Hoptoad -->
      </appender>
      
      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
          <pattern>
            %-4relative [%thread] %-5level %logger{30} - %msg%n
          </pattern>
        </encoder>
      </appender>
      
      <root level="DEBUG">
        <appender-ref ref="HOPTOAD" />
        <appender-ref ref="CONSOLE" />
      </root>
    </configuration>

## MDC (Mapped Diagnostic Context)
For several logging variables, the HoptoadAppender uses MDC to provide the appropriate varaibles
to include in the Hoptoad notice.  These should be configured using a filter or some other way
of injecting the variables into the MDC of the thread.

The following variables are required in the MDC:

 * com.gs.logging.HoptoadLayout.ENVIRONMENT_NAME
 
The following variables are optional:

 * com.gs.logging.HoptoadLayout.PROJECT_ROOT
 * com.gs.logging.HoptoadLayout.REQUEST_URL

If the REQUEST_URL is specified, then the following can also be set:

 * com.gs.logging.HoptoadLayout.REQUEST_COMPONENT (required)
 * com.gs.logging.HoptoadLayout.REQUEST_ACTION
 * com.gs.logging.HoptoadLayout.REQUEST_PARAMS
 * com.gs.logging.HoptoadLayout.SESSION_PARAMS
 * com.gs.logging.HoptoadLayout.CGI_PARAMS
 
The REQUEST_PARAMS, SESSION_PARAMS and CGI_PARAMS should be ampersand delimited, URL encoded key/value pairs.

## License
slf4j-hoptoad is released under the Apache 2 License.
