package org.folio.rest.impl;

import java.io.BufferedReader;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.function.BiConsumer;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.LoggerFactory;

import javax.mail.internet.MimeMultipart;
import javax.ws.rs.core.Response;

import org.folio.rest.annotations.Validate;
import org.folio.rest.jaxrs.resource.AdminResource;
import org.folio.rest.tools.utils.LRUCache;
import org.folio.rest.tools.utils.LogUtil;
import org.folio.rest.tools.utils.OutStream;

public class AdminAPI implements AdminResource {

  private static final io.vertx.core.logging.Logger log    = LoggerFactory.getLogger(AdminAPI.class);
  private static final DecimalFormat DECFORMAT = new DecimalFormat("###.##");
  private static LRUCache<Date, String> jvmMemoryHistory   = LRUCache.newInstance(100);
  
  @Validate
  @Override
  public void putAdminLoglevel(String authorization, Level level, String javaPackage,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
      throws Exception {

    try {
      JsonObject updatedLoggers = LogUtil.updateLogConfiguration(javaPackage, level2level(level));
      OutStream os = new OutStream();
      os.setData(updatedLoggers);
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(PutAdminLoglevelResponse.withJsonOK(os)));
    } catch (Exception e) {
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(PutAdminLoglevelResponse
        .withPlainInternalServerError("ERROR" + e.getMessage())));
      log.error(e.getMessage(), e);
    }

  }
  @Validate
  @Override
  public void getAdminLoglevel(String authorization, Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) throws Exception {

    try {
      JsonObject loggers = LogUtil.getLogConfiguration();
      OutStream os = new OutStream();
      os.setData(loggers);
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(PutAdminLoglevelResponse.withJsonOK(os)));
    } catch (Exception e) {
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(PutAdminLoglevelResponse
        .withPlainInternalServerError("ERROR" + e.getMessage())));
      log.error(e.getMessage(), e);
    }
  }

  private java.util.logging.Level level2level(Level level) {
    return java.util.logging.Level.parse(level.name());
  }

  @Override
  public void postAdminUpload(String authorization, PersistMethod persistMethod, String busAddress, String fileName,
      MimeMultipart entity, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {

    /**
     * THIS FUNCTION WILL NEVER BE CALLED - HANDLED IN THE RestVerticle class
     *
     * http://localhost:8083/admin/upload?file_name=test1&bus_address=circ.uploads.items.imports&persist_method=SAVE_AND_NOTIFY
     *
     */
  }


  @Validate
  @Override
  public void putAdminCollstats(String authorization, Reader entity, Handler<AsyncResult<Response>> asyncResultHandler,
      Context vertxContext) throws Exception {
    /**
     * calls  the MongoStatsPrinter which is a periodic hook that is run periodically to print collection stats to the log based on the collections requested
     * here
     */
    try {
      BufferedReader br = new BufferedReader(entity);
      String line;
      Buffer buffer = Buffer.buffer();
      while((line = br.readLine()) != null){
        buffer.appendString(line);
      }
      JsonObject job = new JsonObject(buffer.toString("UTF8"));
      MongoStatsPrinter.addCollection(job);
      OutStream os = new OutStream();
      os.setData(MongoStatsPrinter.getCollection());
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(PutAdminCollstatsResponse.withJsonOK(os)));
    } catch (Exception e) {
        asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(PutAdminLoglevelResponse
          .withPlainInternalServerError("ERROR" + e.getMessage())));
        log.error(e.getMessage(), e);
    }

  }

  @Override
  public void putAdminJstack(String authorization, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
      throws Exception {
    // TODO Auto-generated method stub

  }

  @Override
  public void getAdminJstack(String authorization, Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext)
      throws Exception {

      final StringBuilder dump = new StringBuilder();
      vertxContext.owner().executeBlocking( code -> {
        try {
          dump.append("<html><body>");
          final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
          final ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 100);
          for (ThreadInfo threadInfo : threadInfos) {
            dump.append(threadInfo.getThreadName());
            final Thread.State state = threadInfo.getThreadState();
            dump.append("</br>   java.lang.Thread.State: ");
            dump.append(state);
            final StackTraceElement[] stackTraceElements = threadInfo.getStackTrace();
            for (final StackTraceElement stackTraceElement : stackTraceElements) {
                dump.append("</br>        at ");
                dump.append(stackTraceElement);
            }
            dump.append("</br></br>");
          }
          dump.append("</body></html>");
          code.complete(dump);
        } catch (Exception e) {
          log.error(e);
          asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(GetAdminJstackResponse
            .withPlainInternalServerError("ERROR" + e.getMessage())));
        }
      }, result -> {
        asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(GetAdminJstackResponse
          .withHtmlOK(result.result().toString())));
      });
  }

  @Override
  public void getAdminMemory(String authorization, boolean history,
      Handler<AsyncResult<Response>> asyncResultHandler, Context vertxContext) throws Exception {
    final StringBuilder dump = new StringBuilder();
    vertxContext.owner().executeBlocking( code -> {
      try {
        dump.append("<br><table>");
        for (MemoryPoolMXBean pool :
          ManagementFactory.getMemoryPoolMXBeans()) {
          MemoryUsage mem = pool.getCollectionUsage();
          MemoryUsage curMem = pool.getUsage();
          MemoryUsage peakMem = pool.getPeakUsage();
          long usageAfterGC = -1;
          long currentUsage = -1;
          double usageAfterGCPercent = -1;
          double currentUsagePercent = -1;
          long peakUsage = -1;
          if(mem != null){
            usageAfterGC = mem.getUsed()/1024/1024;
            usageAfterGCPercent = (double)mem.getUsed()/(double)mem.getCommitted()*100; //Mimic jstat behavior
          }
          if(curMem != null){
            currentUsage = curMem.getUsed()/1024/1024;
            if(curMem.getMax() > 0){
              currentUsagePercent = (double)curMem.getUsed()/(double)curMem.getCommitted()*100; //Mimic jstat behavior
            }
          }
          if(peakMem != null){
            peakUsage = peakMem.getUsed()/1024/1024*100;
          }
          dump
            .append("<tr><td>name: ").append(pool.getName()).append("   </td>")
            .append("<td>memory usage after latest gc: <b>").append(usageAfterGC).append("</b>MB.   </td>")
            .append("<td>type: ").append(pool.getType()).append("   </td>")
            .append("<td>estimate of memory usage: <b>").append(currentUsage).append("</b>MB.   </td>")
            .append("<td>peak usage: ").append(peakUsage).append("MB.   </td>")
            .append("<td> % used memory after GC: <b>").append(DECFORMAT.format(usageAfterGCPercent)).append("</b>  </td>")
            .append("<td> % used memory current: <b>").append(DECFORMAT.format(currentUsagePercent)).append("</b>   </td></tr>");
        }
        dump.append("</table><br><br>");
        final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        final MemoryUsage memInfo = memoryMXBean.getHeapMemoryUsage();
        long memCommittedToJVMByOS = memInfo.getCommitted();
        long memUsedByJVM = memInfo.getUsed();
        dump.append("<b>Total: </b> Memory used/available(MB): ").append((memUsedByJVM/1024/1024))
        .append("/").append(memCommittedToJVMByOS/1024/1024).append("<br>");
        if(history){
          StringBuilder historyMem = new StringBuilder();
          jvmMemoryHistory.put(new Date(), dump.toString());
          BiConsumer<Date, String> biConsumer = (key,value) -> historyMem.append(key.toInstant().toString() + "<br>" + value);
          jvmMemoryHistory.forEach(biConsumer);
          code.complete(historyMem);
        }
        else{
          jvmMemoryHistory.clear();
          code.complete(dump);
        }
      } catch (Exception e) {
        log.error(e);
        asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(GetAdminJstackResponse
          .withPlainInternalServerError("ERROR" + e.getMessage())));
      }
    }, result -> {
      asyncResultHandler.handle(io.vertx.core.Future.succeededFuture(GetAdminJstackResponse
        .withHtmlOK(result.result().toString())));
    });
  }
}
