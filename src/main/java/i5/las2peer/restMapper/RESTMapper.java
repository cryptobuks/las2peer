package i5.las2peer.restMapper;
import i5.las2peer.restMapper.annotations.*;
import i5.las2peer.restMapper.data.*;
import i5.las2peer.restMapper.data.PathTree.PathNode;
import i5.las2peer.restMapper.exceptions.NoMethodFoundException;
import i5.las2peer.restMapper.exceptions.NotSupportedUriPathException;


import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import i5.las2peer.restMapper.tools.ValidationResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;

import javax.xml.xpath.XPathFactory;



/**
 * Maps REST requests to Methods
 * Very simple and only supports basic stuff (map paths and extract path parameters and map query parameters)
 * @author Alexander
 *
 */
public class RESTMapper {

    public static final String SERVICES_TAG = "services";
    public static final String END_PATH_PARAMETER = "}";
    public static final String START_PATH_PARAMETER = "{";
    public static final String DELETE = "delete";
    public static final String GET = "get";
    public static final String PUT = "put";
    public static final String POST = "post";
    public static final String DEFAULT_TAG = "default";
    public static final String ANNOTATION_TAG = "annotation";
    public static final String CONTENT_ANNOTATION = "content";
    public static final String QUERY_ANNOTATION = "query";
    public static final String PATH_ANNOTATION = "path";
    public static final String INDEX_TAG = "index";
    public static final String PARAMETER_TAG = "parameter";
    public static final String PARAMTERS_TAG = "parameters";
    public static final String TYPE_TAG = "type";
    public static final String PATH_TAG = PATH_ANNOTATION;
    public static final String HTTP_METHOD_TAG = "httpMethod";
    public static final String METHOD_TAG = "method";
    public static final String VERSION_TAG = "version";
    public static final String DEFAULT_SERVICE_VERSION = "1.0";
    public static final String METHODS_TAG = "methods";
    public static final String NAME_TAG = "name";
    public static final String SERVICE_TAG = "service";
    public static final String PATH_PARAM_BRACES = "{}";
    public static final String HEADER_ANNOTATION ="header" ;
    public static final String HEADERS_ANNOTATION ="headers" ;

    private static final HashMap<String,Class<?>> classMap= new HashMap<String, Class<?>>();
    public static final String XML = ".xml";
    public static final String DEFAULT_MIME_SEPARATOR = ",";
    public static final String CONSUMES_TAG = "consumes";
    public static final String DEFAULT_CONSUMES_MIME_TYPE = "*";
    public static final String DEFAULT_PRODUCES_MIME_TYPE = "text/plain";
    public static final String PRODUCES_TAG = "produces";
    public static final String DEFAULT_MIME_PARAMETER_SEPARATOR = ";";
    public static final String ACCEPT_ALL_MIME_TYPES = "*/*";


    /**
	 * default constructor
	 */
	public RESTMapper()
	{

	}
	/**
	 * accepts a (service) class and creates an XML file from it
	 * containing all method/parameter information using annotations in the code
	 * @param cl class to extract information fromm
	 * @return XML file
	 * @throws Exception
	 */
	public static String getMethodsAsXML(Class<?> cl) throws Exception
	{
		DocumentBuilderFactory dbFactory;
		DocumentBuilder dBuilder;
		Document doc;
		dbFactory = DocumentBuilderFactory.newInstance();
		Element root;
		Element methodsNode;
		
		dBuilder = dbFactory.newDocumentBuilder();
		doc=dBuilder.newDocument();
		root=doc.createElement(SERVICE_TAG);
		root.setAttribute(NAME_TAG, cl.getName());
		
		methodsNode=doc.createElement(METHODS_TAG);
		root.appendChild(methodsNode);
		doc.appendChild(root);
		
		//gather method and annotation information from class
		Method[] methods = cl.getMethods();	
		Annotation[] classAnnotations=cl.getAnnotations();
		String version=DEFAULT_SERVICE_VERSION;
        String pathPrefix="";
        String[] consumesGlobal=new String[]{DEFAULT_CONSUMES_MIME_TYPE};
        String producesGlobal= DEFAULT_PRODUCES_MIME_TYPE;
        for(Annotation classAnnotation : classAnnotations)
        {
            if(classAnnotation instanceof Version)//get service version if available
            {
                version = ((Version) classAnnotation).value();
            }
            else if (classAnnotation instanceof Path)//path prefix is later applied to all @Path for methods
            {
                pathPrefix=((Path) classAnnotation).value();

            }
            else if (classAnnotation instanceof Consumes)//provides default @Consumes (which MIME to accept)
            {
                consumesGlobal=((Consumes) classAnnotation).value();

            }
            else if (classAnnotation instanceof Produces)//provides default @Produces (which MIME to expect)
            {
                producesGlobal=((Produces) classAnnotation).value();

            }
        }



        pathPrefix=pathPrefix.trim();//ignore empty spaces
        pathPrefix = formatPath(pathPrefix);

        root.setAttribute(VERSION_TAG, version);

        if(!pathPrefix.isEmpty())
            root.setAttribute(PATH_TAG, pathPrefix);

        root.setAttribute(PRODUCES_TAG,producesGlobal);
        root.setAttribute(CONSUMES_TAG, join(consumesGlobal, DEFAULT_MIME_SEPARATOR));
		
		for (Method method : methods) { //create method information
			Annotation[] annotations=method.getAnnotations();
			Annotation[][] parameterAnnotations = method.getParameterAnnotations();	
			String httpMethod = getHttpMethod(annotations);


			//only valid method, if there is a http method and @Path annotation
			if(httpMethod.isEmpty())
				continue;

            String path=null;
			if(method.isAnnotationPresent(Path.class))
            {
                path=formatPath(method.getAnnotation(Path.class).value());
            }
            else if(!pathPrefix.isEmpty())
            {

            }
            else //no path information given
                continue;






            Element methodNode=doc.createElement(METHOD_TAG);
			
			
			methodNode.setAttribute(NAME_TAG, method.getName());
			methodNode.setAttribute(HTTP_METHOD_TAG, httpMethod);
            if(path!=null&&!path.isEmpty())
			    methodNode.setAttribute(PATH_TAG, path);
			methodNode.setAttribute(TYPE_TAG, (method.getReturnType().getName()));



            String consumes;
            if(httpMethod.equals(POST))//@consumes only for POST requests
            {
                if(method.isAnnotationPresent(Consumes.class))//local method @Consumes overrides global class @Consumes
                {
                    consumes=join(method.getAnnotation(Consumes.class).value(), DEFAULT_MIME_SEPARATOR);
                    methodNode.setAttribute(CONSUMES_TAG, consumes.trim());
                }


            }
            String produces;
            if(method.isAnnotationPresent(Produces.class))//local method @Consumes overrides global class @Consumes
            {
                produces=method.getAnnotation(Produces.class).value();
                methodNode.setAttribute(PRODUCES_TAG, produces.trim());
            }



			Element parameters =doc.createElement(PARAMTERS_TAG);
			methodNode.appendChild(parameters);
			
			//handle parameters
			Class<?>[] parameterTypes = method.getParameterTypes();			
			for (int i = 0; i < parameterAnnotations.length; i++) 
	    	{//i:=parameterPos
				Element parameter =doc.createElement(PARAMETER_TAG);
				parameter.setAttribute(INDEX_TAG, Integer.toString(i));
				parameter.setAttribute(TYPE_TAG, (parameterTypes[i].getName()));
				String parameterAnnotation=null;
				String parameterName=null;
				String parameterDefault=null;
				
	    		for (int j = 0; j < parameterAnnotations[i].length; j++) 
	    		{//j:=AnnotationNr
					Annotation ann=parameterAnnotations[i][j];
					//check for parameter annotation type
					if(ann instanceof PathParam)
					{
						String paramName=((PathParam) ann).value();
						parameterAnnotation=PATH_ANNOTATION;
						parameterName=paramName;
					}	
					else if(ann instanceof QueryParam)
					{
						String paramName=((QueryParam) ann).name();
						parameterAnnotation=QUERY_ANNOTATION;
						parameterName=paramName;
                        parameterDefault=((QueryParam) ann).defaultValue();
					}
					else if(ann instanceof ContentParam)
					{
						parameterAnnotation=CONTENT_ANNOTATION;
						parameterName="";
					}
					else if(ann instanceof DefaultValue)
					{
                        parameterDefault= ((DefaultValue) ann).value();
						
					}
                    else if(ann instanceof HeaderParam)
                    {
                        String paramName=((HeaderParam) ann).name();
                        parameterAnnotation=HEADER_ANNOTATION;
                        parameterName=paramName;
                        parameterDefault=((HeaderParam) ann).defaultValue();

                    }
                    else if(ann instanceof HttpHeaders)
                    {
                        parameterAnnotation=HEADERS_ANNOTATION;
                        parameterName="";

                    }
					
					if(parameterAnnotation!=null)	//if a non-exposed parameter is used, works only if default value is provided
						parameter.setAttribute(ANNOTATION_TAG, parameterAnnotation);
					
					if(parameterName!=null) //not needed for content annotation or if non-exposed parameter is used
						parameter.setAttribute(NAME_TAG, parameterName);
					
					//default value is optional
					if(parameterDefault!=null)
						parameter.setAttribute(DEFAULT_TAG, parameterDefault);
					
				}
	    		parameters.appendChild(parameter);
			}
			methodsNode.appendChild(methodNode);
		}
			
		
		return XMLtoString(doc);
	}

    /**
     * Formats path to the expected format
     * @param path
     * @return
     */
    private static String formatPath(String path)
    {
        path=path.replaceAll("(^/)|(/$)","");//remove trailing / for convenience
        return path;
    }

    /**
     * Retruns all occurances of match in s as an integer array
     * @param s
     * @param match
     * @return
     */
    private static Integer[] getOccurrences(String s, String match)
    {
        ArrayList<Integer> occurrences=new ArrayList<Integer>();
        int index = s.indexOf(match);
        while (index >= 0) {
            occurrences.add(index);
            index = s.indexOf(match, index + 1);
        }
        Integer[] result=new Integer[occurrences.size()];
        occurrences.toArray(result);
        return result;


    }

    public static String mergeXMLs(String[] xmls) throws ParserConfigurationException
	{
		
		DocumentBuilderFactory dbFactory;
		DocumentBuilder dBuilder;
		Document doc;		
		dbFactory = DocumentBuilderFactory.newInstance();	
		dBuilder = dbFactory.newDocumentBuilder();
		doc=dBuilder.newDocument();
		Element root=doc.createElement(SERVICES_TAG);
		doc.appendChild(root);

        for(String xml : xmls)
        {
            try
            {
                Document local = dBuilder.parse(new InputSource(new StringReader(xml)));
                doc.getDocumentElement().appendChild(doc.importNode(local.getDocumentElement(), true));
            }
            catch(SAXException e)
            {
                e.printStackTrace();
            }
            catch(IOException e)
            {
                e.printStackTrace();
            }
        }
		doc.getDocumentElement().normalize();
		return XMLtoString(doc);
		
	}
    /**
     * creates a tree from the class data xml
     * the tree can then be used to map requests directly to the proper services and methods
     * @param xml XML containing service class information
     * @return tree structure for request mapping
     * @throws Exception
     */
    public static PathTree getMappingTree(String xml) throws Exception
    {
        return getMappingTree(xml,false,new ValidationResult());
    }

	/**
	 * creates a tree from the class data xml
	 * the tree can then be used to map requests directly to the proper services and methods
	 * @param xml XML containing service class information
     * @param validate if a path-annotation validation should be performed
     * @param result validation result, as reference parameter, if validate is true
	 * @return tree structure for request mapping
	 * @throws Exception
	 */
	public static PathTree getMappingTree(String xml, boolean validate, ValidationResult result) throws Exception
	{
        XPath _xPath = XPathFactory.newInstance().newXPath();
		DocumentBuilderFactory dbFactory;
		DocumentBuilder dBuilder;
		Document doc;		
		dbFactory = DocumentBuilderFactory.newInstance();
        result.setValid(true); //assume everything is right
		
		dBuilder = dbFactory.newDocumentBuilder();
		doc=dBuilder.parse(new InputSource(new StringReader(xml)));
		
		PathTree rootTree=new PathTree();
		PathNode root=rootTree.getRoot();
		
		// start tree with http methods
		root.addChild(POST);
		root.addChild(PUT);
		root.addChild(GET);
		root.addChild(DELETE);
		
		//for each service in the XML
		NodeList serviceNodeList =(NodeList) _xPath.compile(".//" + SERVICE_TAG).evaluate(doc, XPathConstants.NODESET);
		for (int i = 0; i < serviceNodeList.getLength(); i++) 
		{
			Element serviceNode =(Element)serviceNodeList.item(i);
			String serviceName=serviceNode.getAttribute(NAME_TAG).trim();
			String serviceVersion=serviceNode.getAttribute(VERSION_TAG).trim();
            String servicePathPrefix="";
            String[] serviceConsumes=new String[]{DEFAULT_CONSUMES_MIME_TYPE}; //if none is given, then allow everything as a Consume-Type
            String serviceProduces=DEFAULT_PRODUCES_MIME_TYPE;
            if(serviceNode.hasAttribute(PATH_TAG))
            {
                servicePathPrefix=serviceNode.getAttribute(PATH_TAG).trim();
            }

            if(serviceNode.hasAttribute(CONSUMES_TAG))
            {
                serviceConsumes=serviceNode.getAttribute(CONSUMES_TAG).trim().split(DEFAULT_MIME_SEPARATOR);
            }
            if(serviceNode.hasAttribute(PRODUCES_TAG))
            {
                serviceProduces=serviceNode.getAttribute(PRODUCES_TAG).trim();
            }

			//for each method in a service
			NodeList methodeNodeList =(NodeList) _xPath.compile(".//" + METHOD_TAG).evaluate(serviceNode, XPathConstants.NODESET);
			for (int j = 0; j < methodeNodeList.getLength(); j++) 
			{
				Element methodNode =(Element)methodeNodeList.item(j);
				String methodName=methodNode.getAttribute(NAME_TAG).trim();
				String methodHttpMethod=methodNode.getAttribute(HTTP_METHOD_TAG).trim().toLowerCase();
				String methodPath="";

				String methodType=methodNode.getAttribute(TYPE_TAG).trim();
                String[] consumes;
                String produces;
                if(methodNode.hasAttribute(PATH_TAG))
                {
                    methodPath=methodNode.getAttribute(PATH_TAG).trim();
                }
                if(methodNode.hasAttribute(CONSUMES_TAG))
                {
                    consumes=methodNode.getAttribute(CONSUMES_TAG).trim().split(DEFAULT_MIME_SEPARATOR);
                }
                else
                    consumes=serviceConsumes; //use global of service, if method has none

                if(methodNode.hasAttribute(PRODUCES_TAG))
                {
                    produces=methodNode.getAttribute(PRODUCES_TAG).trim();
                }
                else
                    produces=serviceProduces; //use global of service, if method has none

                if(!servicePathPrefix.isEmpty())//prepend class path prefix, if available
                    methodPath= String.format("%s/%s", formatPath(servicePathPrefix), methodPath);

				//begin traversing tree, start from http method node
				PathNode currentNode=root.getChild(methodHttpMethod);
				
				//is there any path to traverse?
				if(methodPath.length()>0){
					
					//transform path in correct format
					if(methodPath.startsWith("/"))
						methodPath=methodPath.substring(1);
					if(methodPath.endsWith("/"))
						methodPath=methodPath.substring(0,methodPath.length()-1);

                    if(validate)//is path annotation well formatted?
                    {

                        Integer[] braceOpen=getOccurrences(methodPath,"{");
                        Integer[] bracesClosed=getOccurrences(methodPath,"}");
                        if(bracesClosed.length!=braceOpen.length) //check if all { closed
                        {
                            result.setValid(false);
                            result.addMessage("Path " + methodPath + " of method " + methodName + " has unequal number of { and }");
                        }
                        else
                        {
                            Integer[] allBraces=new Integer[braceOpen.length+bracesClosed.length];//check if no {{}}
                            int u=0;
                            int lastClosed=-1;
                            for(int k = 0; k < braceOpen.length; k++)
                            {
                                if(!(braceOpen[k]<bracesClosed[k])||lastClosed>=braceOpen[k])
                                {
                                    result.setValid(false);
                                    result.addMessage("Path " + methodPath + " of method " + methodName + " has {} inside of {}");
                                    break;
                                }
                                lastClosed=bracesClosed[k];
                            }


                        }
                    }
					
					//for each URI path segment
					String[] pathParts=methodPath.split("/");

                    for(String pathPart : pathParts)
                    {
                        //if it is a variable parameter in the path...
                        if(pathPart.startsWith(START_PATH_PARAMETER) && pathPart.endsWith(END_PATH_PARAMETER))//PathParams are in {}
                        {
                            currentNode.addChild(PATH_PARAM_BRACES); //add it as a child with {} as name (parameter node)
                            currentNode = currentNode.getChild(PATH_PARAM_BRACES); //and set is as the current node
                            //add the name of the parameter to a list, for later value mapping
                            currentNode.addPathParameterName(pathPart.substring(1, pathPart.length() - 1));
                        }
                        else
                        {
                            currentNode.addChild(pathPart); //text content of path as node name
                            currentNode = currentNode.getChild(pathPart);    //set new node as active node
                        }
                    }
				}
				//get parameter information from the method
				NodeList parameterNodeList =
						(NodeList) _xPath.compile(".//" + PARAMETER_TAG).evaluate(methodNode, XPathConstants.NODESET);
				ParameterData[] parameters=new ParameterData[parameterNodeList.getLength()];
				
				
				for (int k = 0; k < parameterNodeList.getLength(); k++) 
				{
					Element parameter =(Element)parameterNodeList.item(k);
					
					int parameterIndex=Integer.parseInt(parameter.getAttribute(INDEX_TAG));
					String parameterType=parameter.getAttribute(TYPE_TAG);
					//check of the optional attributes
					String parameterAnnotation=null;					
					if(parameter.hasAttribute(ANNOTATION_TAG))
						parameterAnnotation=parameter.getAttribute(ANNOTATION_TAG).toLowerCase();
					String parameterName=null;
					if(parameter.hasAttribute(NAME_TAG))
						parameterName=parameter.getAttribute(NAME_TAG);
					String parameterDefault=null;
					if(parameter.hasAttribute(DEFAULT_TAG))
                    {
						parameterDefault=parameter.getAttribute(DEFAULT_TAG);
                    }

                    if(validate&&parameterName!=null&&parameterAnnotation!=null&&parameterAnnotation.equals(PATH_ANNOTATION))
                    {

                        int index=methodPath.indexOf("{"+parameterName+"}");

                        if(index<=-1)
                        {
                            result.setValid(false);
                            result.addMessage("Path " + methodPath + " of method " + methodName + " lacks the parameter \"" + parameterName+"\"");
                        }
                    }
					
					//create array sorted by the occurrence of the parameter in the method declaration
					parameters[parameterIndex]=
							new ParameterData(parameterAnnotation, parameterIndex,
									parameterName, parameterType, parameterDefault);
				}
				//currentNode is the node, where the URI path traversion stopped, so these paths are then mapped to this method
				//since multiple methods can respond to a single path, a node can store a set of methods from different services
				currentNode.addMethodData(new MethodData(serviceName, serviceVersion, methodName,methodType,consumes,produces,parameters));
			}
		}
		return rootTree;
	}
	
	
	/**
	 * gets the proper HTTP method from the used annotations
	 * @param annotations array of annotations to look for HTTP-Method information
	 * @return HTTP Method (put,post,get etc...)
	 */
	private static String getHttpMethod(Annotation[] annotations) {
		String httpMethod="";
		for (Annotation ann : annotations) 
		{
			if(ann instanceof POST)
			{
				httpMethod=POST;
			}
			else if(ann instanceof PUT)
			{
				httpMethod=PUT;
			}
			else if(ann instanceof GET)
			{
				httpMethod=GET;
			}
			else if(ann instanceof DELETE)
			{
				httpMethod=DELETE;
			}
		}
		return httpMethod;
	}


	/**
	 * receives a request and tries to map it to an existing service and method
	 * @param tree structure to use for the mapping process
	 * @param httpMethod HTTP method of the request
	 * @param uri URI path of the request
	 * @param variables array of parameter/value pairs of the request (query variables)
	 * @param content content of the HTTP body
     * @param contentType MIME-type of the data sent in the POST request
     * @param returnType Accept HTTP Header
	 * @return array of matching services and methods, parameter values are already pre-filled.
	 * @throws Exception
	 */
	public static InvocationData[] parse(PathTree tree, String httpMethod, String uri, Pair<String>[] variables, String content, String contentType, String returnType, Pair<String>[] headers) throws Exception
    {
        httpMethod=httpMethod.toLowerCase(); //for robustness

		if(!contentType.isEmpty())
        {
            int consumesParamSeparator=contentType.indexOf(";");
            if(consumesParamSeparator>-1)
            {
                contentType=contentType.substring(0,consumesParamSeparator); //filter only first part (MIME Type)
            }
            contentType=contentType.trim();
        }





        String[] returnTypes=getAcceptedTypes(returnType);


		//map input values from uri path and variables to the proper method parameters
		HashMap<String,String> parameterValues=new HashMap<String,String> ();
		
		if(uri.startsWith("/"))			
			uri=uri.substring(1);
		
		//start with creating a value mapping using the provided variables
        for(Pair<String> variable : variables)
        {
            parameterValues.put(variable.getOne(), variable.getTwo());
        }
        for(Pair<String> header : headers)
        {
            parameterValues.put(header.getOne(), header.getTwo());
        }
		
		
		//begin traversing the tree from one of the http method nodes
		PathNode currentNode=tree.getRoot().getChild(httpMethod);
		
		if(currentNode==null)//if not supported method
			throw new NotSerializableException(httpMethod);
		
		if(uri.trim().length()>0)//is there any URI path?
		{
			String[] uriSplit=uri.split("/");
            for(String anUriSplit : uriSplit)
            {
                PathNode nextNode = currentNode.getChild(anUriSplit); //get child node with segment name

                if(nextNode == null)//maybe a PathParam?
                {
                    currentNode = currentNode.getChild(PATH_PARAM_BRACES);
                    if(currentNode == null)//is it a PathParam?
                    {
                        throw new NotSupportedUriPathException(httpMethod + " " + uri);
                    }

                    String[] paramNames = currentNode.listPathParameterNames();//it is a PathParam, so get all given names of it
                    for(String paramName : paramNames)
                    {
                        parameterValues.put(paramName, anUriSplit); //map the value provided in the URI path to the stored parameter names
                    }

                }
                else
                {
                    currentNode = nextNode; //continue in tree
                }
            }
		}
		//so all segments of the URI where handled, current node must contain the correct method, if there is any
		MethodData[] methodData=currentNode.listMethodData(); 
		if(methodData==null || methodData.length==0)//no method mapped to the URI path?
		{
			throw new NoMethodFoundException(httpMethod+" "+uri);
		}
		//create data needed to invoke the methods stored in this node
		ArrayList<InvocationData> invocationData=new ArrayList<InvocationData>();

        boolean consumesMIME=httpMethod.equals(POST)&&!contentType.isEmpty();//important vor handling @Consumes


        for(MethodData aMethodData : methodData)
        {

            if(consumesMIME)//is POST and has MIME Type
            {
                String[] methodConsumes=aMethodData.getConsumes();
                boolean foundMatch=false;
                for(String methodConsume : methodConsumes)
                {
                    int wildcardPos = methodConsume.indexOf("*");
                    String toMatch;
                    if(wildcardPos > -1)
                    {
                        toMatch = methodConsume.substring(0, wildcardPos); //filter only first part (MIME Type)
                    }
                    else
                    {
                        toMatch = methodConsume;
                    }

                    if(contentType.startsWith(toMatch)) //found a match no need to check other allowed types
                    {
                        foundMatch=true;
                        break;
                    }
                }

                if(!foundMatch)//method MIME Type does not match, skip method
                {
                    continue;
                }
            }

            int matchLevel=0;
            if(!returnTypes[0].equals(ACCEPT_ALL_MIME_TYPES))//client wants specific types
            {
                String produces=aMethodData.getProduces();
                for(int i = 0; i < returnTypes.length; i++)//find best match level (array is already sorted from best to worst)
                {

                    String type = returnTypes[i];
                   //System.out.println(type);
                   //System.out.println(produces);

                    if(produces.matches(type))
                    {

                        matchLevel=i+1;
                        break;//all after that are worse anyway
                    }

                }
                if(matchLevel==0)//if level is 0, the returnType of the method does not match anything the client accepts -> skip method
                    continue;
            }




            ParameterData[] parameters = aMethodData.getParameters();

            Serializable[] values = new Serializable[parameters.length]; //web connector uses Serializable for invocation
            Class<?>[] types = new Class<?>[parameters.length];
            boolean abort = false;
            for(int j = 0; j < parameters.length; j++)
            {

                ParameterData param = parameters[j];

                if(param.getAnnotation() != null && param.getAnnotation().equals(CONTENT_ANNOTATION)) //if it's a content annotation
                {
                    values[j] = (Serializable) RESTMapper.castToType(content, param.getType()); //fill it with the given content
                    types[j] = param.getType();

                }
                else if(param.getAnnotation() != null && param.getAnnotation().equals(HEADERS_ANNOTATION)) //if it's a content annotation
                {

                    values[j] = (Serializable) RESTMapper.castToType(RESTMapper.mergeHeaders(headers), param.getType()); //fill it with the given headers
                    types[j] = param.getType();

                }
                else
                {
                    if(param.getName() != null && parameterValues.containsKey(param.getName()))//if parameter has a name (given by an annotation) and a value given
                    {
                        values[j] = (Serializable) RESTMapper.castToType(parameterValues.get(param.getName()), param.getType()); //use the created value mapping to assign a value
                        types[j] = param.getType();

                    }
                    else if(param.hasDefaultValue())//if no name, then look for default value
                    {
                        values[j] = (Serializable) param.getDefaultValue();
                        types[j] = param.getType();
                    }
                    else //no value could be assigned to the parameter
                    {

                        abort = true;
                        break;
                    }
                }

            }

            if(!abort)//return only methods which can be invoked
                invocationData.add(
                        new InvocationData(aMethodData.getServiceName(),
                                           aMethodData.getServiceVersion(), aMethodData.getName(),
                                           aMethodData.getType(), aMethodData.getProduces(),
                                           matchLevel, values, types));

        }


        Collections.sort(invocationData,new InvocationDataComperator());//sort for better accept header matches

		InvocationData[] result=new InvocationData[invocationData.size()];
		invocationData.toArray(result);
		return result;
	}

    /**
     * Extracts all acceptable types from Accept Header value
     * @param returnType Accept Header string.
     * @return sorted array (by priority) of acceptable MIME Types
     */
    protected static String[] getAcceptedTypes(String returnType)
    {
        if(returnType.isEmpty())
            return new String[]{ACCEPT_ALL_MIME_TYPES};

        String[] returnTypeMediaRange=returnType.split(DEFAULT_MIME_SEPARATOR);
        ArrayList<AcceptHeaderType> accepts= new ArrayList<AcceptHeaderType>();
        for(int i = 0; i < returnTypeMediaRange.length; i++)
        {
            String media = returnTypeMediaRange[i].trim();
            int qvaluePos=media.indexOf(";q=");
            float qvalue=1;

            if(qvaluePos>-1)
            {
                try
                {
                    qvalue=Float.parseFloat(media.substring(qvaluePos+3,media.length()));
                }
                catch(NumberFormatException e)
                {
                    qvalue=1;
                }

                media=media.substring(0,qvaluePos);

            }

            accepts.add(new AcceptHeaderType(media,qvalue));

        }
        Collections.sort(accepts, new AcceptHeaderTypeComperator());
        String[] result=new String[accepts.size()];
        for(int i = 0; i < accepts.size(); i++)
        {
           result[i]=accepts.get(i).getType().replaceAll("[*]","\\\\w+");

        }

        return result;
    }

    private static String mergeHeaders(Pair<String>[] headers)
    {
        StringBuilder sb = new StringBuilder();
        for(Pair<String> header : headers)
        {
            sb.append(String.format("%s: %s\n", header.getOne(), header.getTwo()));

        }
        return sb.toString();
    }

    /**
	 * prints readable XML
	 * @param doc XML document
	 * @return readable XML
	 */
	public static String XMLtoString(Document doc)
	{
		if(doc!=null)
		{			
			try
			{				
				Transformer t = TransformerFactory.newInstance().newTransformer();
				StreamResult out = new StreamResult(new StringWriter());
				t.setOutputProperty(OutputKeys.INDENT, "yes"); //pretty printing
				t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
				t.setOutputProperty("{http://xml.apache.org/xalan}indent-amount", "2");
				t.transform(new DOMSource(doc),out);
				return out.getWriter().toString();
			}
			catch(Exception e)
			{
				e.printStackTrace();
				return "";
			}
			
		}
		else
			return "";
	}
	
	/**
	 * Casts received String values to appropriate types the method demands
	 * Currently only supports Strings and primitive types
	 * @param val String value to cast
	 * @param class1 Type the parameter expects
	 * @return returns the proper type as an Object
	 * @throws Exception
	 */
	public static Object castToType(String val, Class<?> class1) throws Exception {
		//Byte		
		if(class1.equals(Byte.class)||class1.equals(byte.class))
		{			
			return Byte.valueOf(val);
		}
		//Short		
		if(class1.equals(Short.class)||class1.equals(short.class))
		{			
			return Short.valueOf(val);
		}
		//Long		
		if(class1.equals(Long.class)||class1.equals(long.class))
		{			
			return Long.valueOf(val);
		}
		//Float		
		if(class1.equals(Float.class)||class1.equals(float.class))
		{			
			return Float.valueOf(val);
		}
		//Double		
		if(class1.equals(Double.class)||class1.equals(double.class))
		{			
			return Double.valueOf(val);
		}
		//Boolean		
		if(class1.equals(Boolean.class)||class1.equals(boolean.class))
		{			
			return Boolean.valueOf(val);
		}
		//Char		
		if(class1.equals(Character.class)||class1.equals(char.class))
		{			
			return val.charAt(0);
		}
		//Integer		
		if(class1.equals(Integer.class)||class1.equals(int.class))
		{			
			return Integer.valueOf(val);
		}
		//String
		if(class1.equals(String.class))
		{			
			return val;
		}
		//not supported type
		throw new Exception("Parameter Type: "+class1.getName() +"not supported!");
		
	}
	/**
	 * Converts a methods return value to String
	 * @param result value to cast to a String
	 * @return String representation of Object
	 */
	public static String castToString(Object result) {
		if(result instanceof String)
		{
			return (String)result;
		}
		if(result instanceof Integer)
		{
			return Integer.toString((Integer) result);
		}
		if(result instanceof Byte)
		{
			return Byte.toString((Byte) result);
		}
		if(result instanceof Short)
		{
			return Short.toString((Short) result);
		}
		if(result instanceof Long)
		{
			return Long.toString((Long) result);
		}
		if(result instanceof Float)
		{
			return Float.toString((Float) result);
		}
		if(result instanceof Double)
		{
			return Double.toString((Double) result);
		}
		if(result instanceof Boolean)
		{
			return Boolean.toString((Boolean) result);
		}
		if(result instanceof Character)
		{
			return Character.toString((Character) result);
		}
		return result.toString(); //desperate measures
	}
	
	/**
	 * Gets the class type based on a string
	 * needed because int.class.getName() can later not be found by the VM behavior
	 * only Strings and primitive types are supported
	 * @param type name of type given by .class.getName()
	 * @return class type
	 * @throws ClassNotFoundException
	 */
	public static Class<?> getClassType(String type) throws ClassNotFoundException
	{
        initClassmap();
        Class<?> result=classMap.get(type);
        if(result!=null)
        {
            return result;
        }
        else
        {
            return Class.forName(type);
        }
	}

    /**
     * Initializes the String to Class mapping HashSet (faster lookup than else if)
     * Needed to map String Notations of Types to actual primitive Types
     */
    private static void initClassmap()
    {
        if(classMap.isEmpty())
        {
            classMap.put("int", int.class);
            classMap.put("float", float.class);
            classMap.put("byte", byte.class);
            classMap.put("short", short.class);
            classMap.put("long", long.class);
            classMap.put("double", double.class);
            classMap.put("char", char.class);
            classMap.put("boolean", boolean.class);

        }
    }

    /**
     * Looks for all xml files in a directory and its subdirectories
     * Reads each file and puts them into a String array
     * @param dir path to the directory
     * @return array of all found XML contents
     * @throws IOException
     */
    public static String[] readAllXMLFromDir(String dir) throws IOException
    {
        File folder = new File(dir);
        ArrayList<File> files = new ArrayList<File>();
        listFilesForFolder(folder, XML,files);

        String[] xmls=new String[files.size()];
        for(int i = 0; i < xmls.length; i++)
        {
            xmls[i]=getFile(files.get(i));

        }
        return xmls;
    }

    /**
     * Reads a given file
     * @param file file to read
     * @return content of file
     * @throws IOException
     */
    public static String getFile(File file) throws IOException
    {
        String content = null;
        FileReader reader = null;
        try {
            reader = new FileReader(file);
            char[] chars = new char[(int) file.length()];
            reader.read(chars);
            content = new String(chars);
            reader.close();
        }

        finally {
            if(reader!=null)
                reader.close();
        }

        return content;
    }

    /**
     * Writes a string to a file
     * @param file file path
     * @param content what to write into the file
     * @throws IOException
     */
    public static void writeFile(String file, String content) throws IOException
    {
        PrintWriter writer=null;
        try {
            writer= new PrintWriter(file, "UTF-8");
            writer.write(content);

        }
        finally
        {
            if(writer!=null)
                writer.close();

        }
    }

    /**
     * Lists all files matching the given type as suffix
     * @param folder parent folder from where to start looking
     * @param type suffix, e.g. ".xml"
     * @param list reference to result array (stores all files found)
     */
    private static void listFilesForFolder(final File folder,String type, ArrayList<File> list) throws IOException {
        try
        {
            for (final File fileEntry : folder.listFiles()) {
                if (fileEntry.isDirectory()) {
                    listFilesForFolder(fileEntry,type,list);
                } else if (fileEntry.getName().toLowerCase().endsWith(type)){
                    list.add(fileEntry);
                }
            }
        }
        catch(Exception e)
        {
            throw new IOException(e);
        }
    }

    /**
     * Joins elements of a string array
     * @param array array which elements to join
     * @param separator string to put between array elements
     * @return joined string containing all array elements
     */
    protected static String join(String[] array, String separator)
    {
        if(array.length==0)
            return "";
        if(array.length==1)
            return array[0];

        StringBuilder sb= new StringBuilder();
        for(int i = 0; i < array.length-1; i++)
        {
           sb.append(array[i]).append(separator);
        }

        sb.append(array[array.length-1]);//convert patterns to regex expressions

        return sb.toString();
    }
}