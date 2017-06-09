/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2012
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4che3.conf.ldap.hl7;

import org.dcm4che3.conf.api.ConfigurationException;
import org.dcm4che3.conf.api.hl7.HL7ApplicationAlreadyExistsException;
import org.dcm4che3.conf.api.hl7.HL7Configuration;
import org.dcm4che3.conf.ldap.LdapDicomConfigurationExtension;
import org.dcm4che3.conf.ldap.LdapUtils;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.hl7.HL7Application;
import org.dcm4che3.net.hl7.HL7DeviceExtension;
import org.dcm4che3.util.StringUtils;

import javax.naming.NameAlreadyBoundException;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.*;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
public class LdapHL7Configuration extends LdapDicomConfigurationExtension
        implements HL7Configuration {

    private static final String CN_UNIQUE_HL7_APPLICATION_NAMES_REGISTRY =
            "cn=Unique HL7 Application Names Registry,";

    private String appNamesRegistryDN;

    private final List<LdapHL7ConfigurationExtension> extensions =
            new ArrayList<LdapHL7ConfigurationExtension>();

    public void addHL7ConfigurationExtension(LdapHL7ConfigurationExtension ext) {
        ext.setHL7Configuration(this);
        extensions.add(ext);
    }

    public boolean removeHL7ConfigurationExtension(
            LdapHL7ConfigurationExtension ext) {
        if (!extensions.remove(ext))
            return false;

        ext.setHL7Configuration(null);
        return true;
    }

    @Override
    public boolean registerHL7Application(String name)
            throws ConfigurationException {
        try {
            registerHL7App(name);
            return true;
        } catch (HL7ApplicationAlreadyExistsException e) {
            return false;
        }
    }

    private String registerHL7App(String name) throws ConfigurationException {
        ensureAppNamesRegistryExists();
        try {
            String dn = hl7appDN(name, appNamesRegistryDN);
            config.createSubcontext(dn,
                    LdapUtils.attrs("hl7UniqueApplicationName", "hl7ApplicationName", name));
            return dn;
        } catch (NameAlreadyBoundException e) {
            throw new HL7ApplicationAlreadyExistsException("HL7 Application '" + name + "' already exists");
        } catch (NamingException e) {
            throw new ConfigurationException(e);
        }
    }

    @Override
    public void unregisterHL7Application(String name)
            throws ConfigurationException {
        if (appNamesRegistryExists())
            try {
                config.destroySubcontext(hl7appDN(name, appNamesRegistryDN));
            } catch (NameNotFoundException e) {
            } catch (NamingException e) {
                throw new ConfigurationException(e);
            }
    }

    private void ensureAppNamesRegistryExists() throws ConfigurationException {
        if (appNamesRegistryDN != null)
            return;
        
        config.ensureConfigurationExists();
        String dn = CN_UNIQUE_HL7_APPLICATION_NAMES_REGISTRY
                + config.getConfigurationDN();
        try {
            if (!config.exists(dn))
                config.createSubcontext(dn,
                        LdapUtils.attrs("hl7UniqueApplicationNamesRegistryRoot", 
                                "cn", "Unique HL7 Application Names Registry"));
        } catch (NamingException e) {
            throw new ConfigurationException(e);
        }
        appNamesRegistryDN = dn;
    }

    private boolean appNamesRegistryExists() throws ConfigurationException {
        if (appNamesRegistryDN != null)
            return true;
        
        if (!config.configurationExists())
            return false;
        
        String dn = CN_UNIQUE_HL7_APPLICATION_NAMES_REGISTRY
                + config.getConfigurationDN();
        try {
            if (!config.exists(dn))
                return false;
        } catch (NamingException e) {
            throw new ConfigurationException(e);
        }
        
        appNamesRegistryDN = dn;
        return true;
    }

    @Override
    public String[] listRegisteredHL7ApplicationNames()
            throws ConfigurationException {
        if (!appNamesRegistryExists())
            return StringUtils.EMPTY_STRING;

        return config.list(appNamesRegistryDN, 
                "(objectclass=hl7UniqueApplicationName)", "hl7ApplicationName");
    }

    @Override
    public HL7Application findHL7Application(String name)
            throws ConfigurationException {
        Device device = config.findDevice(
            "(&(objectclass=hl7Application)(hl7ApplicationName=" + name + "))",
            name);
        HL7DeviceExtension hl7Ext = device.getDeviceExtension(HL7DeviceExtension.class);
        return hl7Ext.getHL7Application(name);
    }

    @Override
    protected void storeChilds(String deviceDN, Device device) throws NamingException {
        HL7DeviceExtension hl7Ext = device.getDeviceExtension(HL7DeviceExtension.class);
        if (hl7Ext == null)
            return;

        for (HL7Application hl7App : hl7Ext.getHL7Applications())
            store(hl7App, deviceDN);
    }

    private void store(HL7Application hl7App, String deviceDN) throws NamingException {
        String appDN = hl7appDN(hl7App.getApplicationName(), deviceDN);
        config.createSubcontext(appDN,
                storeTo(hl7App, deviceDN, new BasicAttributes(true)));
        for (LdapHL7ConfigurationExtension ext : extensions)
            ext.storeChilds(appDN, hl7App);
    }

    private String hl7appDN(String name, String deviceDN) {
        return LdapUtils.dnOf("hl7ApplicationName" , name, deviceDN);
    }

    private Attributes storeTo(HL7Application hl7App, String deviceDN, Attributes attrs) {
        attrs.put(new BasicAttribute("objectclass", "hl7Application"));
        LdapUtils.storeNotNullOrDef(attrs, "hl7ApplicationName",
                hl7App.getApplicationName(), null);
        LdapUtils.storeNotEmpty(attrs, "hl7AcceptedSendingApplication",
                hl7App.getAcceptedSendingApplications());
        LdapUtils.storeNotEmpty(attrs, "dcmOtherApplicationNames", hl7App.getOtherApplicationNames());
        LdapUtils.storeNotEmpty(attrs, "hl7AcceptedMessageType",
                hl7App.getAcceptedMessageTypes());
        LdapUtils.storeNotNullOrDef(attrs, "hl7DefaultCharacterSet",
                hl7App.getHL7DefaultCharacterSet(), "ASCII");
        LdapUtils.storeConnRefs(attrs, hl7App.getConnections(), deviceDN);
        LdapUtils.storeNotNullOrDef(attrs, "dicomInstalled", hl7App.getInstalled(), null);
        for (LdapHL7ConfigurationExtension ext : extensions)
            ext.storeTo(hl7App, deviceDN, attrs);
        return attrs;
    }

    @Override
    protected void loadChilds(Device device, String deviceDN)
            throws NamingException, ConfigurationException {
        NamingEnumeration<SearchResult> ne =
                config.search(deviceDN, "(objectclass=hl7Application)");
        try {
            if (!ne.hasMore())
                return;

            HL7DeviceExtension hl7Ext = new HL7DeviceExtension();
            device.addDeviceExtension(hl7Ext);
            do {
                hl7Ext.addHL7Application(
                        loadHL7Application(ne.next(), deviceDN, device));
            } while (ne.hasMore());
        } finally {
            LdapUtils.safeClose(ne);
        }
    }

    private HL7Application loadHL7Application(SearchResult sr, String deviceDN,
            Device device) throws NamingException, ConfigurationException {
        Attributes attrs = sr.getAttributes();
        HL7Application hl7app = new HL7Application(LdapUtils.stringValue(attrs.get("hl7ApplicationName"), null));
        loadFrom(hl7app, attrs);
        for (String connDN : LdapUtils.stringArray(attrs.get("dicomNetworkConnectionReference")))
            hl7app.addConnection(LdapUtils.findConnection(connDN, deviceDN, device));
        for (LdapHL7ConfigurationExtension ext : extensions)
            ext.loadChilds(hl7app, sr.getNameInNamespace());
        return hl7app;
    }

    protected void loadFrom(HL7Application hl7app, Attributes attrs) throws NamingException {
        hl7app.setAcceptedSendingApplications(LdapUtils.stringArray(attrs.get("hl7AcceptedSendingApplication")));
        hl7app.setOtherApplicationNames(LdapUtils.stringArray(attrs.get("hl7OtherApplicationName")));
        hl7app.setAcceptedMessageTypes(LdapUtils.stringArray(attrs.get("hl7AcceptedMessageType")));
        hl7app.setHL7DefaultCharacterSet(LdapUtils.stringValue(attrs.get("hl7DefaultCharacterSet"), "ASCII"));
        hl7app.setInstalled(LdapUtils.booleanValue(attrs.get("dicomInstalled"), null));
        for (LdapHL7ConfigurationExtension ext : extensions)
            ext.loadFrom(hl7app, attrs);
    }

    @Override
    protected void mergeChilds(Device prev, Device device, String deviceDN)
            throws NamingException {
        HL7DeviceExtension prevHL7Ext =
                prev.getDeviceExtension(HL7DeviceExtension.class);
        HL7DeviceExtension hl7Ext = 
                device.getDeviceExtension(HL7DeviceExtension.class);

        if (prevHL7Ext != null)
            for (String appName : prevHL7Ext.getHL7ApplicationNames()) {
                if (hl7Ext == null || !hl7Ext.containsHL7Application(appName))
                    config.destroySubcontextWithChilds(hl7appDN(appName, deviceDN));
            }

        if (hl7Ext == null)
            return;

        for (HL7Application hl7app : hl7Ext.getHL7Applications()) {
            String appName = hl7app.getApplicationName();
            if (prevHL7Ext == null || !prevHL7Ext.containsHL7Application(appName)) {
                store(hl7app, deviceDN);
            } else
                merge(prevHL7Ext.getHL7Application(appName), hl7app, deviceDN);
        }
    }

    private void merge(HL7Application prev, HL7Application app, String deviceDN)
            throws NamingException {
        String appDN = hl7appDN(app.getApplicationName(), deviceDN);
        config.modifyAttributes(appDN, storeDiffs(prev, app, deviceDN, 
                new ArrayList<ModificationItem>()));
        for (LdapHL7ConfigurationExtension ext : extensions)
            ext.mergeChilds(prev, app, appDN);
    }

    private List<ModificationItem> storeDiffs(HL7Application a, HL7Application b,
            String deviceDN, List<ModificationItem> mods) {
        LdapUtils.storeDiff(mods, "hl7AcceptedSendingApplication",
                a.getAcceptedSendingApplications(),
                b.getAcceptedSendingApplications());
        LdapUtils.storeDiff(mods, "hl7OtherApplicationName",
                a.getOtherApplicationNames(),
                b.getOtherApplicationNames());
        LdapUtils.storeDiff(mods, "hl7AcceptedMessageType",
                a.getAcceptedMessageTypes(),
                b.getAcceptedMessageTypes());
        LdapUtils.storeDiffObject(mods, "hl7DefaultCharacterSet",
                a.getHL7DefaultCharacterSet(),
                b.getHL7DefaultCharacterSet(), "ASCII");
        LdapUtils.storeDiff(mods, "dicomNetworkConnectionReference",
                a.getConnections(),
                b.getConnections(),
                deviceDN);
        LdapUtils.storeDiffObject(mods, "dicomInstalled",
                a.getInstalled(),
                b.getInstalled(), null);
        for (LdapHL7ConfigurationExtension ext : extensions)
            ext.storeDiffs(a, b, mods);
        return mods;
    }

    @Override
    protected void register(Device device, List<String> dns) throws ConfigurationException {
        HL7DeviceExtension hl7Ext = device.getDeviceExtension(HL7DeviceExtension.class);
        if (hl7Ext == null)
            return;

        for (String name : hl7Ext.getHL7ApplicationNames()) {
            if (!name.equals("*"))
                dns.add(registerHL7App(name));
        }
    }

    @Override
    protected void registerDiff(Device prev, Device device, List<String> dns) throws ConfigurationException {
        HL7DeviceExtension prevHL7Ext = prev.getDeviceExtension(HL7DeviceExtension.class);
        if (prevHL7Ext == null) {
            register(device, dns);
            return;
        }

        HL7DeviceExtension hl7Ext = device.getDeviceExtension(HL7DeviceExtension.class);
        if (hl7Ext == null)
            return;

        for (String name : hl7Ext.getHL7ApplicationNames()) {
            if (!name.equals("*") && prevHL7Ext.getHL7Application(name) == null)
                dns.add(registerHL7App(name));
        }
    }

    @Override
    protected void markForUnregister(Device prev, Device device, List<String> dns) {
        HL7DeviceExtension prevHL7Ext = prev.getDeviceExtension(HL7DeviceExtension.class);
        if (prevHL7Ext == null)
            return;

        HL7DeviceExtension hl7Ext = device.getDeviceExtension(HL7DeviceExtension.class);
        for (String name : prevHL7Ext.getHL7ApplicationNames()) {
            if (!name.equals("*") && (hl7Ext == null || hl7Ext.getHL7Application(name) == null))
                dns.add(hl7appDN(name, appNamesRegistryDN));
        }
    }

    @Override
    protected void markForUnregister(String deviceDN, List<String> dns)
            throws NamingException, ConfigurationException {
        if (!appNamesRegistryExists())
            return;

        NamingEnumeration<SearchResult> ne =
                config.search(deviceDN, "(objectclass=hl7Application)", StringUtils.EMPTY_STRING);
        try {
            while (ne.hasMore()) {
                String rdn = ne.next().getName();
                if (!rdn.equals("hl7ApplicationName=*"))
                    dns.add(rdn + ',' + appNamesRegistryDN);
            }
        } finally {
            LdapUtils.safeClose(ne);
        }
    }
}
