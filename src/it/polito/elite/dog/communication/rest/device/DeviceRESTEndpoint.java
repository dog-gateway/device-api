/*
 * Dog - Device Rest Endpoint
 * 
 * Copyright (c) 2013-2014 Dario Bonino, Luigi De Russis and Teodoro Montanaro
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package it.polito.elite.dog.communication.rest.device;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicReference;

import javax.measure.Measure;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.device.Constants;
import org.osgi.service.log.LogService;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.dataformat.xml.JacksonXmlModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;
import it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi;
import it.polito.elite.dog.communication.rest.device.command.ClimateSchedulePayload;
import it.polito.elite.dog.communication.rest.device.command.CommandPayload;
import it.polito.elite.dog.communication.rest.device.command.DailyClimateSchedulePayload;
import it.polito.elite.dog.communication.rest.device.command.DoublePayload;
import it.polito.elite.dog.communication.rest.device.command.ExplicitTeachInPayload;
import it.polito.elite.dog.communication.rest.device.command.HSBColorPayload;
import it.polito.elite.dog.communication.rest.device.command.MeasurePayload;
import it.polito.elite.dog.communication.rest.device.command.RGBColorPayload;
import it.polito.elite.dog.communication.rest.device.command.StringPayload;
import it.polito.elite.dog.communication.rest.device.status.AllDeviceStatesResponsePayload;
import it.polito.elite.dog.communication.rest.device.status.DeviceStateResponsePayload;
import it.polito.elite.dog.core.devicefactory.api.DeviceFactory;
import it.polito.elite.dog.core.housemodel.api.HouseModel;
import it.polito.elite.dog.core.library.jaxb.Controllables;
import it.polito.elite.dog.core.library.jaxb.Device;
import it.polito.elite.dog.core.library.jaxb.DogHomeConfiguration;
import it.polito.elite.dog.core.library.jaxb.ObjectFactory;
import it.polito.elite.dog.core.library.model.ControllableDevice;
import it.polito.elite.dog.core.library.model.DeviceCostants;
import it.polito.elite.dog.core.library.model.DeviceDescriptor;
import it.polito.elite.dog.core.library.model.DeviceStatus;
import it.polito.elite.dog.core.library.model.devicecategory.Controllable;
import it.polito.elite.dog.core.library.model.state.State;
import it.polito.elite.dog.core.library.model.statevalue.StateValue;
import it.polito.elite.dog.core.library.util.Executor;
import it.polito.elite.dog.core.library.util.LogHelper;

/**
 * 
 * @author <a href="mailto:dario.bonino@polito.it">Dario Bonino</a>
 * @author <a href="mailto:luigi.derussis@polito.it">Luigi De Russis</a>
 * @see <a href="http://elite.polito.it">http://elite.polito.it</a>
 * 
 */
@Path("/api/v1/devices/")
public class DeviceRESTEndpoint implements DeviceRESTApi
{
    // the service logger
    private LogHelper logger;

    // the bundle context reference to extract information on the entire Dog
    // status
    private BundleContext context;

    // reference for the HouseModel
    private AtomicReference<HouseModel> houseModel;

    // reference for the DeviceFactory
    private AtomicReference<DeviceFactory> deviceFactory;

    // registered payloads
    private Vector<Class<? extends CommandPayload<?>>> payloads;

    // the instance-level mapper
    private ObjectMapper mapper;

    // the XML Mapper
    private XmlMapper xmlMapper;

    /**
     * Constructor
     */
    public DeviceRESTEndpoint()
    {
        // init the house model atomic reference
        this.houseModel = new AtomicReference<HouseModel>();

        // init the device factory atomic reference
        this.deviceFactory = new AtomicReference<DeviceFactory>();

        // init the set of allowed payloads
        this.payloads = new Vector<Class<? extends CommandPayload<?>>>();
        this.payloads.add(ClimateSchedulePayload.class);
        this.payloads.add(DailyClimateSchedulePayload.class);
        // it is really mandatory that double payload precedes measure payload
        // to avoid matching pure doubles to measures with no unit.
        this.payloads.add(DoublePayload.class);
        this.payloads.add(MeasurePayload.class);
        this.payloads.add(HSBColorPayload.class);
        this.payloads.add(RGBColorPayload.class);
        this.payloads.add(ExplicitTeachInPayload.class);
        this.payloads.add(StringPayload.class);

        // initialize the instance-wide object mapper (JSON)
        this.mapper = new ObjectMapper();
        // set the mapper pretty printing
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        // avoid empty arrays and null values
        this.mapper.configure(SerializationFeature.WRITE_EMPTY_JSON_ARRAYS,
                false);
        this.mapper.setSerializationInclusion(Include.NON_NULL);

        // create an introspector for parsing both Jackson and JAXB annotations
        AnnotationIntrospector jackson = new JacksonAnnotationIntrospector();
        AnnotationIntrospector jaxb = new JaxbAnnotationIntrospector();
        AnnotationIntrospector fullIntrospector = AnnotationIntrospector
                .pair(jackson, jaxb);
        // make deserializer use both Jackson and JAXB annotations
        this.mapper.setAnnotationIntrospector(fullIntrospector);

        // initialize the instance-wide XML mapper
        // create a JacksonXmlModule to customize XML parsing
        JacksonXmlModule xmlModule = new JacksonXmlModule();
        // disable wrapper elements
        xmlModule.setDefaultUseWrapper(false);
        // create a new XML mapper
        this.xmlMapper = new XmlMapper(xmlModule);
        // avoid failure on unknown properties
        this.xmlMapper
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.xmlMapper.setSerializationInclusion(Include.NON_EMPTY);
        // pretty printing
        // mapper.enable(SerializationFeature.INDENT_OUTPUT);
        // exploit existing JAXB annotations
        // ad interim solution to be removed when migration to Jackson will
        // complete.
        // mapper.registerModule(new JaxbAnnotationModule());
        this.xmlMapper.setAnnotationIntrospector(jaxb);

    }

    /**
     * Bundle activation, stores a reference to the context object passed by the
     * framework to get access to system data, e.g., installed bundles, etc.
     * 
     * @param context
     */
    public void activate(BundleContext context)
    {
        // store the bundle context
        this.context = context;

        // init the logger with a null logger
        this.logger = new LogHelper(this.context);

        // log the activation
        this.logger.log(LogService.LOG_INFO, "Activated....");
    }

    /**
     * Prepare the bundle to be deactivated...
     */
    public void deactivate()
    {
        // null the context
        this.context = null;

        // log deactivation
        this.logger.log(LogService.LOG_INFO, "Deactivated...");

        // null the logger
        this.logger = null;
    }

    /**
     * Bind the HouseModel service (before the bundle activation)
     * 
     * @param houseModel
     *            the HouseModel service to add
     */
    public void addedHouseModel(HouseModel houseModel)
    {
        // store a reference to the HouseModel service
        this.houseModel.set(houseModel);
    }

    /**
     * Unbind the HouseModel service
     * 
     * @param houseModel
     *            the HouseModel service to remove
     */
    public void removedHouseModel(HouseModel houseModel)
    {
        this.houseModel.compareAndSet(houseModel, null);
    }

    /**
     * Bind the DeviceFactory service (before the bundle activation)
     * 
     * @param deviceFactory
     *            the DeviceFactory service to add
     */
    public void addedDeviceFactory(DeviceFactory deviceFactory)
    {
        // store a reference to the HouseModel service
        this.deviceFactory.set(deviceFactory);
    }

    /**
     * Unbind the DeviceFactory service
     * 
     * @param deviceFactory
     *            the DeviceFactory service to remove
     */
    public void removedDeviceFactory(DeviceFactory deviceFactory)
    {
        this.deviceFactory.compareAndSet(deviceFactory, null);
    }

    @Override
    public Response options()
    {
        return Response.ok("").header("Access-Control-Allow-Origin", "*")
                .header("Access-Control-Allow-Headers",
                        "origin, content-type, accept, authorization")
                .header("Access-Control-Allow-Methods",
                        "GET, POST, PUT, OPTIONS, HEAD")
                .build();
    }

    /*
     * (non-Javadoc)
     * 
     * @see it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi#
     * getAllDevicesInJson()
     */
    @Override
    public String getAllDevicesInJson(HttpServletResponse httpResponse)
    {
        String devicesJSON = "";

        this.setCORSSupport(httpResponse);

        // get the JAXB object containing all the configured devices
        DogHomeConfiguration dhc = this.getAllDevices();

        try
        {
            devicesJSON = this.mapper
                    .writeValueAsString(dhc.getControllables().get(0));
        }
        catch (Exception e)
        {
            this.logger.log(LogService.LOG_ERROR,
                    "Error in creating the JSON representing all the configured devices",
                    e);
        }

        // if no devices are available, send a 404 Not found HTTP response
        // assume, as before, that only one Controllables tag exists
        boolean noDevices = dhc.getControllables().get(0).getDevice().isEmpty();

        if (devicesJSON.isEmpty() || noDevices)
        {
            // launch the exception responsible for sending the HTTP response
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        else
        {
            return devicesJSON;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi#
     * getAllDevicesInXml ()
     */
    @Override
    public String getAllDevicesInXml(HttpServletResponse httpResponse)
    {
        String devicesXML = "";

        this.setCORSSupport(httpResponse);

        // get the JAXB object containing all the configured devices
        DogHomeConfiguration dhc = this.getAllDevices();

        // create the XML for replying the request
        devicesXML = this.generateXML(dhc);

        // if no devices are available, send a 404 Not found HTTP response
        // assume, as before, that only one Controllables tag exists
        boolean noDevices = dhc.getControllables().get(0).getDevice().isEmpty();

        if (devicesXML.isEmpty() || noDevices)
        {
            // launch the exception responsible for sending the HTTP response
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        else
        {
            return devicesXML;
        }
    }

    /**
     * Get all the devices configured in Dog from the {@link HouseModel} in
     * their "clean" format, e.g., without all the network-related information
     * and unneeded tabs or newlines
     * 
     * @return a {@link DogHomeConfiguration} object with all the devices
     *         information
     */
    private DogHomeConfiguration getAllDevices()
    {
        // create a JAXB Object Factory for adding the proper header...
        ObjectFactory factory = new ObjectFactory();
        DogHomeConfiguration dhc = factory.createDogHomeConfiguration();

        // check if the HouseModel service is available
        if (this.houseModel.get() != null)
        {
            // get all the devices from the HouseModel
            Controllables controllables = this.houseModel.get()
                    .getSimpleDevices().get(0);

            dhc.getControllables().add(controllables);
        }

        return dhc;
    }

    /*
     * (non-Javadoc)
     * 
     * @see it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi#
     * getDeviceInJson(java.lang.String)
     */
    @Override
    public String getDeviceInJson(String deviceId,
            HttpServletResponse httpResponse)
    {
        String deviceJSON = "";

        this.setCORSSupport(httpResponse);

        // get the requested device configuration, in JAXB
        DogHomeConfiguration dhc = this.getDevice(deviceId);

        if (dhc.getControllables().get(0).getDevice() != null)
        {
            // get the JAXB representation of the desired device
            Device requestedDevice = dhc.getControllables().get(0).getDevice()
                    .get(0);

            try
            {
                deviceJSON = this.mapper.writeValueAsString(requestedDevice);
            }
            catch (Exception e)
            {
                this.logger.log(LogService.LOG_ERROR,
                        "Error in creating the JSON representing all the configured devices",
                        e);
            }

            return deviceJSON;
        }
        else
        {
            // the requested device is not present, send a 404 Not found HTTP
            // response
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi#
     * getDeviceInXml (java.lang.String)
     */
    @Override
    public String getDeviceInXml(String deviceId,
            HttpServletResponse httpResponse)
    {
        String deviceXML = "";

        this.setCORSSupport(httpResponse);

        // get the requested device configuration
        DogHomeConfiguration dhc = this.getDevice(deviceId);

        if (dhc.getControllables().get(0).getDevice() != null)
        {
            // create the XML for replying the request
            deviceXML = this.generateXML(dhc);

            return deviceXML;
        }
        else
        {
            // the requested device is not present, send a 404 Not found HTTP
            // response
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    /**
     * 
     * Get the configuration of the device identified by the parameter deviceId
     * from the {@link HouseModel} and perform some "cleaning" operations, such
     * as removing all the network-related information and removing unneeded
     * tabs or newlines
     * 
     * @param deviceId
     *            the device unique identifier
     * @return a {@link DogHomeConfiguration} object with the required device
     *         information
     */
    private DogHomeConfiguration getDevice(String deviceId)
    {
        ObjectFactory factory = new ObjectFactory();
        DogHomeConfiguration dhc = factory.createDogHomeConfiguration();

        // check if the HouseModel service is available
        if (this.houseModel.get() != null)
        {
            // create a JAXB Object Factory for adding the proper header...

            Controllables controllables = factory.createControllables();

            // get the desired device from the HouseModel service
            for (Device device : this.houseModel.get().getSimpleDevices().get(0)
                    .getDevice())
            {
                if (device.getId().equalsIgnoreCase(deviceId))
                {
                    // add the device to its container
                    controllables.getDevice().add(device);
                }
            }

            dhc.getControllables().add(controllables);
        }
        return dhc;
    }

    /*
     * (non-Javadoc)
     * 
     * @see it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi#
     * updateDeviceLocation(java.lang.String, java.lang.String)
     */
    @Override
    public Response updateDeviceLocation(String deviceId, String location,
            HttpServletResponse httpResponse)
    {
        // set and init the variable used to store the HTTP response that will
        // be sent by exception to the client
        Status response = Response.Status.EXPECTATION_FAILED;

        if (location != null && !location.isEmpty())
        {
            // create filter for getting the desired device
            String deviceFilter = String.format("(&(%s=*)(%s=%s))",
                    Constants.DEVICE_CATEGORY, DeviceCostants.DEVICEURI,
                    deviceId);

            try
            {
                // try to read the value from the JSON
                Device deviceLocation = this.mapper.readValue(location,
                        Device.class);

                // get the device service references
                ServiceReference<?>[] deviceService = this.context
                        .getAllServiceReferences(
                                org.osgi.service.device.Device.class.getName(),
                                deviceFilter);

                // only one device with the given deviceId can exists in the
                // framework...
                if (deviceService != null && deviceService.length == 1)
                {
                    // get the OSGi service pointed by the current device
                    // reference
                    Object device = this.context.getService(deviceService[0]);

                    if ((device != null)
                            && (device instanceof ControllableDevice))
                    {
                        // get the device instance
                        ControllableDevice currentDevice = (ControllableDevice) device;
                        // get the associated device descriptor
                        DeviceDescriptor currentDeviceDescr = currentDevice
                                .getDeviceDescriptor();

                        // update the device location, if available
                        if ((deviceLocation.getIsIn() != null)
                                && (!deviceLocation.getIsIn().isEmpty()))
                        {
                            currentDeviceDescr
                                    .setLocation(deviceLocation.getIsIn());

                            // check if the DeviceFactory service is available
                            if (this.deviceFactory.get() != null)
                            {
                                // update the device configuration
                                this.deviceFactory.get()
                                        .updateDevice(currentDeviceDescr);
                                // set the variable used to store the HTTP
                                // response by the right value
                                // OK: the device location was successfully
                                // updated
                                response = Response.Status.OK;

                            }
                            else
                            {
                                this.logger.log(LogService.LOG_WARNING,
                                        "Impossible to update the device location: the Device Factory is not available!");
                                // set the variable used to store the HTTP
                                // response by the right value
                                // PRECONDITION_FAILED: impossible to update the
                                // device location since the Device Factory is
                                // not available
                                // it was the best response status available
                                response = Response.Status.PRECONDITION_FAILED;
                            }
                        }
                    }

                    // releases all the services object referenced at the
                    // beginning of the method
                    for (ServiceReference<?> singleServiceReference : deviceService)
                    {
                        this.context.ungetService(singleServiceReference);
                    }
                }
            }
            catch (Exception e)
            {
                this.logger.log(LogService.LOG_ERROR,
                        "Error in updating the location of device " + deviceId,
                        e);
                // set the variable used to store the HTTP response by the right
                // value
                // NOT_MODIFIED: impossible to update the location of the device
                response = Response.Status.NOT_MODIFIED;
            }
        }

        // launch the exception responsible for sending the HTTP response
        if (response != Response.Status.OK)
            throw new WebApplicationException(response);

        return Response.ok().header("Access-Control-Allow-Origin", "*").build();
    }

    /*
     * (non-Javadoc)
     * 
     * @see it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi#
     * updateDeviceDescription(java.lang.String, java.lang.String)
     */
    @Override
    public Response updateDeviceDescription(String deviceId, String description,
            HttpServletResponse httpResponse)
    {
        // set and init the variable used to store the HTTP response that will
        // be sent by exception to the client
        Status response = Response.Status.EXPECTATION_FAILED;

        if (description != null && !description.isEmpty())
        {
            // create filter for getting the desired device
            String deviceFilter = String.format("(&(%s=*)(%s=%s))",
                    Constants.DEVICE_CATEGORY, DeviceCostants.DEVICEURI,
                    deviceId);

            try
            {
                // try to read the value from the JSON
                Device deviceDescription = this.mapper.readValue(description,
                        Device.class);

                // get the device service references
                ServiceReference<?>[] deviceService = this.context
                        .getAllServiceReferences(
                                org.osgi.service.device.Device.class.getName(),
                                deviceFilter);

                // only one device with the given deviceId can exists in the
                // framework...
                if (deviceService != null && deviceService.length == 1)
                {
                    // get the OSGi service pointed by the current device
                    // reference
                    Object device = this.context.getService(deviceService[0]);

                    if ((device != null)
                            && (device instanceof ControllableDevice))
                    {
                        // get the device instance
                        ControllableDevice currentDevice = (ControllableDevice) device;
                        // get the associated device descriptor
                        DeviceDescriptor currentDeviceDescr = currentDevice
                                .getDeviceDescriptor();

                        // update the device description, if available
                        if ((deviceDescription.getDescription() != null)
                                && (!deviceDescription.getDescription()
                                        .isEmpty()))
                        {
                            currentDeviceDescr.setDescription(
                                    deviceDescription.getDescription());

                            // check if the DeviceFactory service is available
                            if (this.deviceFactory.get() != null)
                            {
                                // update the device configuration
                                this.deviceFactory.get()
                                        .updateDevice(currentDeviceDescr);
                                // set the variable used to store the HTTP
                                // response by the right value
                                // OK: the description was successfully updated
                                response = Response.Status.OK;
                            }
                            else
                            {
                                this.logger.log(LogService.LOG_WARNING,
                                        "Impossible to update the device description: the Device Factory is not available!");
                                // set the variable used to store the HTTP
                                // response by the right value
                                // PRECONDITION_FAILED: impossible to update the
                                // device description since the Device Factory
                                // is not available
                                // it was the best response status available
                                response = Response.Status.PRECONDITION_FAILED;
                            }
                        }
                    }

                    // releases all the services object referenced at the
                    // beginning of the method
                    for (ServiceReference<?> singleServiceReference : deviceService)
                    {
                        this.context.ungetService(singleServiceReference);
                    }
                }
            }
            catch (Exception e)
            {
                this.logger.log(LogService.LOG_ERROR,
                        "Error in updating the description of device "
                                + deviceId,
                        e);
                // set the variable used to store the HTTP response by the right
                // value
                // NOT_MODIFIED: impossible to update the description of the
                // device
                // it was the best response status available
                response = Response.Status.NOT_MODIFIED;
            }
        }

        // launch the exception responsible for sending the HTTP response
        if (response != Response.Status.OK)
            throw new WebApplicationException(response);

        return Response.ok().header("Access-Control-Allow-Origin", "*").build();
    }

    /*
     * (non-Javadoc)
     * 
     * @see it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi#
     * getAllDeviceStatus()
     */
    public String getAllDeviceStatus(HttpServletResponse httpResponse)
    {
        // the response
        String responseAsString = "";
        boolean listIsEmpty = true;

        this.setCORSSupport(httpResponse);

        // get all the installed device services
        try
        {
            // get the device service references
            ServiceReference<?>[] allDevices = this.context
                    .getAllServiceReferences(
                            org.osgi.service.device.Device.class.getName(),
                            null);

            // check not null
            if (allDevices != null)
            {
                // create an AllDeviceStatesResponsePayload
                AllDeviceStatesResponsePayload responsePayload = new AllDeviceStatesResponsePayload();

                // create an array of DeviceStateResponsePayloads
                DeviceStateResponsePayload[] deviceStateResponsePayload = new DeviceStateResponsePayload[allDevices.length];

                // set the array as part of the response payload
                responsePayload.setDevicesStatus(deviceStateResponsePayload);

                // iterate over all devices
                for (int i = 0; i < allDevices.length; i++)
                {
                    // get the OSGi service pointed by the current device
                    // reference
                    Object device = this.context.getService(allDevices[i]);

                    // check if the service belongs to the set of dog devices
                    if (device instanceof ControllableDevice)
                    {
                        // get the device instance
                        ControllableDevice currentDevice = (ControllableDevice) device;

                        // get the response payload for the current device
                        deviceStateResponsePayload[i] = this
                                .getControllableStatus(currentDevice,
                                        allDevices[i]);
                        // if we are here it means that the list will not be
                        // empty
                        listIsEmpty = false;
                    }

                    this.context.ungetService(allDevices[i]);
                }
                // store the device
                responsePayload.setDevicesStatus(deviceStateResponsePayload);

                // convert the response body to json
                responseAsString = this.mapper
                        .writeValueAsString(responsePayload);

                // Releases all the services object referenced at the beginning
                // of the method
                for (ServiceReference<?> singleServiceReference : allDevices)
                {
                    this.context.ungetService(singleServiceReference);
                }
            }

        }
        catch (Exception e)
        {
            this.logger.log(LogService.LOG_ERROR,
                    "Error while composing the response", e);
        }

        // if the responseAsString variable is empty we have to send an HTTP
        // response
        // 404 Not found
        if (responseAsString.isEmpty() || listIsEmpty == true)
        {
            // launch the exception responsible for sending the HTTP response
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        else
            return responseAsString;
    }

    /*
     * (non-Javadoc)
     * 
     * @see it.polito.elite.dog.communication.rest.device.api.DeviceRESTApi#
     * getDeviceStatus(java.lang.String)
     */
    @Override
    public String getDeviceStatus(String deviceId,
            HttpServletResponse httpResponse)
    {
        // the response
        String responseAsString = "";
        boolean listIsEmpty = true;

        this.setCORSSupport(httpResponse);

        // create filter for getting the desired device
        String deviceFilter = String.format("(&(%s=*)(%s=%s))",
                Constants.DEVICE_CATEGORY, DeviceCostants.DEVICEURI, deviceId);

        try
        {
            // get the device service references
            ServiceReference<?>[] deviceService = this.context
                    .getAllServiceReferences(
                            org.osgi.service.device.Device.class.getName(),
                            deviceFilter);
            if (deviceService != null)
            {
                // create a DeviceStateResponsePayload
                DeviceStateResponsePayload deviceStateResponsePayload = new DeviceStateResponsePayload();

                // only one device with the given deviceId can exists in the
                // framework
                if (deviceService.length == 1)
                {
                    // get the OSGi service pointed by the current device
                    // reference
                    Object device = this.context.getService(deviceService[0]);

                    if (device instanceof ControllableDevice)
                    {
                        // get the device instance
                        ControllableDevice currentDevice = (ControllableDevice) device;

                        // get the response payload
                        deviceStateResponsePayload = this.getControllableStatus(
                                currentDevice, deviceService[0]);
                        // if we are here it means that the list will not be
                        // empty
                        listIsEmpty = false;
                    }

                    this.context.ungetService(deviceService[0]);
                }

                // convert the response body to json
                responseAsString = this.mapper
                        .writeValueAsString(deviceStateResponsePayload);

                // Releases all the services object referenced at the beginning
                // of the method
                for (ServiceReference<?> singleServiceReference : deviceService)
                {
                    this.context.ungetService(singleServiceReference);
                }
            }
        }
        catch (Exception e)
        {
            this.logger.log(LogService.LOG_ERROR,
                    "Error while composing the response for the status of "
                            + deviceId,
                    e);
        }

        // if the responseAsString variable is empty we have to send an HTTP
        // response
        // 404 Not found
        if (responseAsString.isEmpty() || listIsEmpty == true)
        {
            // launch the exception responsible for sending the HTTP response
            throw new WebApplicationException(Response.Status.NOT_FOUND);
        }
        else
            return responseAsString;
    }

    /**
     * Build the Jackson representation for the status of a given
     * {@link ControllableDevice} object.
     * 
     * @param device
     *            the {@link ControllableDevice} to query for the status
     * @param deviceService
     *            the OSGi service reference for the given
     *            {@link ControllableDevice}
     * @return a {@link DeviceStateResponsePayload} containing the proper
     *         response to the status API
     */
    private DeviceStateResponsePayload getControllableStatus(
            ControllableDevice device, ServiceReference<?> deviceService)
    {
        // init
        DeviceStateResponsePayload deviceStateResponsePayload = null;

        // get the device descriptor
        DeviceDescriptor deviceDescriptor = device.getDeviceDescriptor();

        // create the response payload
        deviceStateResponsePayload = new DeviceStateResponsePayload();

        // set the device id
        deviceStateResponsePayload.setId(deviceDescriptor.getDeviceURI());

        // set the activation status of the device
        deviceStateResponsePayload.setActive(Boolean.valueOf(
                (String) deviceService.getProperty(DeviceCostants.ACTIVE)));

        // get the device status
        Map<String, State> allStates = null;
        DeviceStatus state = ((Controllable) device).getState();
        if (state != null)
        {
            allStates = state.getStates();
        }

        // check if the device state is available, i.e., not null
        if (allStates != null)
        {
            // iterate over all states
            for (String stateKey : allStates.keySet())
            {
                // get the current state
                State currentState = allStates.get(stateKey);

                // get the values associate to the current state
                StateValue currentStateValues[] = currentState
                        .getCurrentStateValue();

                // create the response-level state values
                Object responseBodyStateValues[] = new Object[currentStateValues.length];

                // iterate over the state values
                for (int j = 0; j < currentStateValues.length; j++)
                {
                    // get state value features
                    HashMap<String, Object> features = currentStateValues[j]
                            .getFeatures();

                    // prepare the map to store in the response
                    // body
                    HashMap<String, Object> responseBodyFeatures = new HashMap<String, Object>();

                    // iterate over the features
                    for (String featureKey : features.keySet())
                    {
                        // check the "value" feature and, if it
                        // is an instance of measure, serialize
                        // it as a String
                        if (featureKey.contains("Value"))
                        {
                            if (features
                                    .get(featureKey) instanceof Measure<?, ?>)
                                responseBodyFeatures.put("value",
                                        features.get(featureKey).toString());
                            else
                                responseBodyFeatures.put("value",
                                        features.get(featureKey));

                        }
                        else
                        {
                            Object value = features.get(featureKey);

                            if ((!(value instanceof String))
                                    || ((value instanceof String)
                                            && (!((String) value).isEmpty())))
                                responseBodyFeatures.put(featureKey,
                                        features.get(featureKey));
                        }

                    }

                    // store the current state value
                    responseBodyStateValues[j] = responseBodyFeatures;
                }

                // store the state
                deviceStateResponsePayload.getStatus().put(
                        currentState.getClass().getSimpleName(),
                        responseBodyStateValues);
            }
        }

        return deviceStateResponsePayload;
    }

    @Override
    public Response executeCommandGet(String deviceId, String commandName,
            HttpServletResponse httpResponse)
    {
        // this.setCORSSupport(httpResponse);
        return this.executeCommand(deviceId, commandName, null);
    }

    @Override
    public Response executeCommandPost(String deviceId, String commandName,
            String commandParameters, HttpServletResponse httpResponse)
    {
        // this.setCORSSupport(httpResponse);
        return this.executeCommand(deviceId, commandName, commandParameters);
    }

    @Override
    public Response executeCommandPut(String deviceId, String commandName,
            String commandParameters, HttpServletResponse httpResponse)
    {
        // this.setCORSSupport(httpResponse);
        return this.executeCommand(deviceId, commandName, commandParameters);
    }

    /**
     * 
     * @param deviceId
     * @param commandName
     * @param commandParameters
     */
    private Response executeCommand(String deviceId, String commandName,
            String commandParameters)
    {

        // set default value for the variable used to store the HTTP response by
        // the right value: EXPECTATION_FAILED (If something goes wrong we will
        // say to user that the command was not executed successfully)
        // it was the best response status available
        Status response = Response.Status.EXPECTATION_FAILED;

        // get the executor instance
        Executor executor = Executor.getInstance();

        // --- Use Jackson to interpret the type of data passed as value ---

        // check if a post/put body is given, it is not an empty JSON object,
        // and convert it into an array of parameters
        // TODO: check if commands can have more than 1 parameter
        if ((commandParameters != null) && (!commandParameters.isEmpty())
                && (!commandParameters.equals("{}")))
        {
            // try to read the payload
            for (int i = 0; i < this.payloads.size(); i++)
            {
                try
                {
                    // try to read the value
                    CommandPayload<?> payload = this.mapper
                            .readValue(commandParameters, this.payloads.get(i));

                    // if payload !=null
                    executor.execute(context, deviceId, commandName,
                            new Object[] { payload.getValue() });

                    // set the variable used to store the HTTP response by the
                    // right value
                    // OK: the command was executed without exception
                    response = Response.Status.OK;

                    break;
                }
                catch (Exception e)
                {
                    // set the variable used to store the HTTP response by the
                    // right value
                    // EXPECTATION_FAILED: An exception occured so the command
                    // was not executed as expected
                    // it was the best response status available
                    response = Response.Status.EXPECTATION_FAILED;
                    // then proceed to the next trial
                }
            }
        }
        else
        {
            // exec the command
            try
            {
                executor.execute(context, deviceId, commandName,
                        new Object[] {});
                // set the variable used to store the HTTP response by the right
                // value
                // OK: the command was executed without exception
                response = Response.Status.OK;
            }
            catch (Exception e)
            {
                // set the variable used to store the HTTP response by the right
                // value
                // EXPECTATION_FAILED: An exception occured so the command was
                // not executed as expected
                // it was the best response status available
                response = Response.Status.EXPECTATION_FAILED;
            }
        }

        if (response == Response.Status.EXPECTATION_FAILED)
        {
            // launch the exception responsible for sending the HTTP response
            throw new WebApplicationException(response);
        }

        return Response.ok().header("Access-Control-Allow-Origin", "*").build();
    }

    /**
     * Generate the XML to be sent
     * 
     * @param dhc
     *            the {@link DogHomeConfiguration} object to marshall
     * @return the corresponding XML
     */
    private String generateXML(DogHomeConfiguration dhc)
    {
        String devicesXML = "";

        if (this.xmlMapper != null)
        {
            try
            {
                // create the XML Output factory needed to correctly serialize
                // the configuration
                XMLOutputFactory xmlOutputFactory = XMLOutputFactory
                        .newFactory();
                // create a string writer for de-serializing on a string
                StringWriter writer = new StringWriter();
                // wrap the string writer with an XMLStreamWriter
                XMLStreamWriter xmlWriter = xmlOutputFactory
                        .createXMLStreamWriter(writer);
                // serialize as XML
                this.xmlMapper.writeValue(xmlWriter, dhc);
                // get the corresponding string.
                devicesXML = writer.getBuffer().toString();
            }
            catch (IOException | XMLStreamException e)
            {
                // the exception can be throw by the JAXB.marshal method...
                this.logger.log(LogService.LOG_ERROR,
                        "Exception in XML Serialization...", e);
            }
        }

        return devicesXML;
    }

    private void setCORSSupport(HttpServletResponse response)
    {
        response.addHeader("Access-Control-Allow-Origin", "*");
    }
}