/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.server.management;


import org.apache.activemq.artemis.core.server.ActiveMQServerLogger;
import org.apache.activemq.artemis.logs.AuditLogger;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.JMException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.security.auth.Subject;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Principal;
import java.util.List;

public class ArtemisMBeanServerGuard implements InvocationHandler {

   private JMXAccessControlList jmxAccessControlList = JMXAccessControlList.createDefaultList();

   public void init() {
      ArtemisMBeanServerBuilder.setGuard(this);
   }

   @Override
   public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (method.getParameterTypes().length == 0)
         return null;

      if (!ObjectName.class.isAssignableFrom(method.getParameterTypes()[0]))
         return null;

      ObjectName objectName = (ObjectName) args[0];
      if ("getAttribute".equals(method.getName())) {
         handleGetAttribute((MBeanServer) proxy, objectName, (String) args[1]);
      } else if ("getAttributes".equals(method.getName())) {
         handleGetAttributes((MBeanServer) proxy, objectName, (String[]) args[1]);
      } else if ("setAttribute".equals(method.getName())) {
         handleSetAttribute((MBeanServer) proxy, objectName, (Attribute) args[1]);
      } else if ("setAttributes".equals(method.getName())) {
         handleSetAttributes((MBeanServer) proxy, objectName, (AttributeList) args[1]);
      } else if ("invoke".equals(method.getName())) {
         handleInvoke(objectName, (String) args[1]);
      }

      return null;
   }

   private void handleGetAttribute(MBeanServer proxy, ObjectName objectName, String attributeName) throws JMException, IOException {
      MBeanInfo info = proxy.getMBeanInfo(objectName);
      String prefix = null;
      for (MBeanAttributeInfo attr : info.getAttributes()) {
         if (attr.getName().equals(attributeName)) {
            prefix = attr.isIs() ? "is" : "get";
         }
      }

      if (prefix != null) {
         try {
            handleInvoke(objectName, prefix + attributeName);
         } catch (SecurityException e) {
            // The security exception message is shown in the attributes tab of the console.
            throw new SecurityException("User not authorized to access attribute: " + attributeName, e);
         }
      }
   }

   private void handleGetAttributes(MBeanServer proxy, ObjectName objectName, String[] attributeNames) throws JMException, IOException {
      for (String attr : attributeNames) {
         handleGetAttribute(proxy, objectName, attr);
      }
   }

   private void handleSetAttribute(MBeanServer proxy, ObjectName objectName, Attribute attribute) throws JMException, IOException {
      String dataType = null;
      MBeanInfo info = proxy.getMBeanInfo(objectName);
      for (MBeanAttributeInfo attr : info.getAttributes()) {
         if (attr.getName().equals(attribute.getName())) {
            dataType = attr.getType();
            break;
         }
      }

      if (dataType == null)
         throw new IllegalStateException("Attribute data type can not be found");

      handleInvoke(objectName, "set" + attribute.getName());
   }

   private void handleSetAttributes(MBeanServer proxy, ObjectName objectName, AttributeList attributes) throws JMException, IOException {
      for (Attribute attr : attributes.asList()) {
         handleSetAttribute(proxy, objectName, attr);
      }
   }

   private boolean canBypassRBAC(ObjectName objectName) {
      return jmxAccessControlList.isInWhiteList(objectName);
   }

   public boolean canInvoke(String object, String operationName) {
      ObjectName objectName = null;
      try {
         objectName = ObjectName.getInstance(object);
      } catch (MalformedObjectNameException e) {
         ActiveMQServerLogger.LOGGER.debug("can't check invoke rights as object name invalid: " + object);
         return false;
      }
      if (canBypassRBAC(objectName)) {
         return true;
      }
      List<String> requiredRoles = getRequiredRoles(objectName, operationName);
      for (String role : requiredRoles) {
         if (currentUserHasRole(role)) {
            return true;
         }
      }
      ActiveMQServerLogger.LOGGER.debug(object + " " + operationName + " " + false);
      return false;
   }

   void handleInvoke(ObjectName objectName, String operationName) throws IOException {
      if (canBypassRBAC(objectName)) {
         return;
      }
      List<String> requiredRoles = getRequiredRoles(objectName, operationName);
      for (String role : requiredRoles) {
         if (currentUserHasRole(role))
            return;
      }
      if (AuditLogger.isResourceLoggingEnabled()) {
         AuditLogger.objectInvokedFailure(objectName, operationName);
      }
      throw new SecurityException("User not authorized to access operation: " + operationName);
   }

   List<String> getRequiredRoles(ObjectName objectName, String methodName) {
      return jmxAccessControlList.getRolesForObject(objectName, methodName);
   }

   public void setJMXAccessControlList(JMXAccessControlList JMXAccessControlList) {
      this.jmxAccessControlList = JMXAccessControlList;
   }

   public static boolean currentUserHasRole(String requestedRole) {

      String clazz;
      String role;
      int index = requestedRole.indexOf(':');
      if (index > 0) {
         clazz = requestedRole.substring(0, index);
         role = requestedRole.substring(index + 1);
      } else {
         clazz = "org.apache.activemq.artemis.spi.core.security.jaas.RolePrincipal";
         role = requestedRole;
      }
      AccessControlContext acc = AccessController.getContext();
      if (acc == null) {
         return false;
      }
      Subject subject = Subject.getSubject(acc);
      if (subject == null) {
         return false;
      }
      for (Principal p : subject.getPrincipals()) {
         if (clazz.equals(p.getClass().getName()) && role.equals(p.getName())) {
            return true;
         }
      }
      return false;
   }
}
