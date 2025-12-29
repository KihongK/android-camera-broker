// IAMRControl.aidl
package com.sk.airbot.amrcontrol;

import com.sk.airbot.amrcontrol.IAMRControlCallback;

interface IAMRControl {
    boolean registerCallback(IAMRControlCallback callback);
    boolean unregisterCallback(IAMRControlCallback callback);
    int sendModuleCommand(in Bundle indata, out Bundle outdata);
	// opMapping 1:manual mapping, 2:auto mapping, 4:stop
	// opMapping 1:manual mapping, 2:auto mapping, 4:stop
    void	opMapping(int op, int delay);
    // opDriving 1: start, 2: pause, 3: resume, 4: stop
    // opDriving 1: start, 2: pause, 3: resume, 4: stop
    void	opDriving(int op, int delay);

	// opSetTargetPosition (x,y,theta): moveTo({x,y,t})
    int		opSetTargetPosition(double x, double y, double t, int delay);

    // opGetMap: retrieve a map data : two files - pgm, yaml
    void	opGetMap(int delay);

    // opReturnToChargingStation
    void	opReturnToChargingStation(int delay);

    //
	void	opControlManualVW(double ms, double rs, int delay);
    void	opRobotTest(int m);

	/*
     opRotate:

        absOrRel: type of rotation command -
	              0: differentially rotate -theta- to the front of robot.
				  1: rotate -theta- to the X-axis of the coordinate of the map.
                  2: rotate - robot self decide to stop rotating.
                  3:  - 360 degree Count-Clockwise rotation.
				  4:  - 360 degree Cclockwise rotation.
	*/
	void    opRotate(int absOrRel, double theta, int delay);

	/* opLidarOnOff : turn on/off the lidar of the AMR. */
	/* ToDo:  */
	void    opLidarOnOff(int op, int delay); /* op 1: On, 4: Off */
	/*
    sensor:
    status
    LidarSendorStatus 2,
    NOT USED: MotorStatus 3, CameraSt 4, LineLaser 5, TofSTat 6, IR - 7, Sonic 8, Battery 9
    data
    SoftwareVersion 10, LidarData 16, RobotSpeed 17
    RobotInfo 11,
	V16:
	MotorStatus 31, RecvIRStatus 32, CliffIRStatus 33, CameraSatus 34, LineLaserStatus 35 not used
	TofStatus 36, BatteryStatus 37
    */
	void    opQuerySensor(int sensor, int delay);
	/* v: start=1, stop=0 */
	/* opAiCalibration: used?
            v: start=1, stop=0 */
	void	opAiCalibration(int v, int delay);

	/* v: start=1, stop=4 */
	void	opChargingBattery(int v, int delay);

	void	opReset(int delay);
	boolean	opOta(String otafile, int delay);
	/* opSendMetaData: map_meta..json
	   맵 공간 분할 등 정보를 저장한 json 파일 전달
		fname: json file path (absolute path)
	*/
	void	opSendMetaData(String fname, int delay);

	boolean opIsConnected();
	boolean opIsMapping();
	boolean opIsNavi();

	int		opSetFactoryMode(boolean bs, long delay);
	int		opSetBatterySleepMode(boolean bs, long delay);
	int		opSetSensorInspectionMode(boolean bs, long delay);
	int		opRepositioningStation(double x, double y, double t, long delay);

	/**
	  opSetTargetPosition 에 회전동작을 함께 보내기 위한 command.
	  @param rot : 로테이션 방법 지정 .
	                 0: 로봇 정면 기준으로 회전
					 1: 맵 좌표계 기준으로 회전
                     2: 로봇에서 회전각 결정
    ***/
    int		opSetTargetPosition2(double x, double y, double t, int rot, long delay);

    boolean opIsFactoryMode();

	/** opCopyMap(String filepath, ) deprecated, use opCopyMap2(String[] files, ) */
    int opCopyMap(String filepath, long delay);
	int opSelfDiagnosisMotor(boolean enable, double distance, double speed, long delay);
	int opFollowMe(boolean bs, long delay);
	int opCopyMap2(in String[] files, long delay);

	/**
	 *  @param result  result of OTA process(SoC+AMR+...)
	 *                 true: send the success message to AMR.
	 *                 false: send the rollback message to AMR.
	**/
	int opSendOTAResult(boolean result, long delay);

	/**
	 * To get the temperature of the AI board.
	 **/
	int opGetTemperature(long delay);

	/**   1. get the current position */
	int opGetCurrentPosition(long delay);
	/**  10. get the version of amr  */
	int opGetAMRVersion(long delay);
	/**  51. emergency stop  */
	int opEmergencyStop(long delay);
	/**  90. Docking.*/
	int opDocking(long delay);

	/** 44. EncMapData */
	int opEncMapData(long delay);

	/** 86. Initialize MapData */
	int opInitMapData(long delay);

	const int MAPPING_START_MANUAL = 1;
	const int MAPPING_START_AUTO = 2;
	const int MAPPING_STOP = 4;

	const int DRIVING_START = 1;
	const int DRIVING_PAUSE = 2;
	const int DRIVING_RESUME = 3;
	const int DRIVING_STOP = 4;

	const int START_CHARGING = 1;
	const int STOP_CHARGING = 4;

	const int MOVESTATE_IDLE = 0; /* initial value: slam,navigation, moving */
	const int MOVESTATE_READY = 7; //NEW 1;
	const int MOVESTATE_MOVE_GOAL = 1; //2;
	const int MOVESTATE_ARRIVED_GOAL = 2; //3;
	const int MOVESTATE_ALTERNATIVE_GOAL = 8; //4;
	const int MOVESTATE_PAUSED	 = 3;
	const int MOVESTATE_START_ROTATION = 5;
	const int MOVESTATE_ROTATION_END = 6;
	const int MOVESTATE_FAIL = 4;

	const int AMR_SENSOR_LIDAR_STATUS		= 2;
	const int AMR_SENSOR_LIDAR_DATA			= 16;
	const int AMR_SENSOR_MOTOR_STATUS		= 31;
	const int AMR_SENSOR_RECV_IR_STATUS		= 32;
	const int AMR_SENSOR_CLIFF_IR_STATUS	= 33;
	const int AMR_SENSOR_CAMERA_STATUS		= 34;
	const int AMR_SENSOR_LINE_LASER_STATUS	= 35;
	const int AMR_SENSOR_TOF_STATUS			= 34;
	const int AMR_SENSOR_BATTERY_STATUS		= 34;

	const int ROTATION_REL = 0;
	const int ROTATION_MAP_ABS = 1;
	const int ROTATION_AUTO = 2;
	const int ROTATION_CCW_360 = 3;
	const int ROTATION_CW_360 = 4;

	const int OTA_NORMAL_BASE = 0;
	const int OTA_START_OTA	  = 0;
	const int OTA_INIT_OTA	  = 1;
	const int OTA_MCU_OTA	  = 2;
	const int OTA_AI_OTA	  = 3;
	const int OTA_AP_OTA	  = 4;
	const int OTA_OTA_OTA	  = 5;
	const int OTA_DONE_OTA	  = 6;
	const int OTA_OTA_SUCCESS = 7;
	const int OTA_OTA_FAIL	  = 8;
	const int OTA_REBOOT	  = 9;

	const int OTA_RECOVERY_BASE = 112;  /* 0x70 */
	const int OTA_RECOVERTY_START_OTA	  = 112;
	const int OTA_RECOVERTY_INIT_OTA	  = 113;
	const int OTA_RECOVERTY_MCU_OTA	  = 114;
	const int OTA_RECOVERTY_AI_OTA	  = 115;
	const int OTA_RECOVERTY_AP_OTA	  = 116;
	const int OTA_RECOVERTY_OTA_OTA	  = 117;
	const int OTA_RECOVERTY_DONE_OTA	  = 118;
	const int OTA_RECOVERTY_OTA_SUCCESS = 119;
	const int OTA_RECOVERTY_OTA_FAIL	  = 120;
	const int OTA_RECOVERTY_REBOOT	  = 121;

	/* ROBOT_STATUS_ & ACTION_STATUS 를 이용해서 로봇의 상태를 확인한다.
	   ex) R_S_NAVIGATION & A_S_START: 자동 맵핑 정상 시작
	       R_S_NAVIGATION & A_S_FAIL : 자동 맵핑 시작 못 함
	 */
	const int ROBOT_STATUS_IDLE				= 0;
	const int ROBOT_STATUS_AUTO_MAPPING		= 1;
	const int ROBOT_STATUS_MANUAL_MAPPING	= 2;
	const int ROBOT_STATUS_NAVIGATION		= 3;
	const int ROBOT_STATUS_RETURN_CHARGER	= 4;
	const int ROBOT_STATUS_DOCKING			= 5;
	const int ROBOT_STATUS_UNDOCKING		= 6;
	const int ROBOT_STATUS_ONSTATION		= 7;
	const int ROBOT_STATUS_FACTORY_NAV		= 8;
	const int ROBOT_STATUS_ERROR			= 9;
	const int ROBOT_STATUS_FOLLOWME			= 10;

	const int ACTION_STATUS_VOID			= 0;
	const int ACTION_STATUS_READY			= 1;
	const int ACTION_STATUS_START			= 2;
	const int ACTION_STATUS_RUN				= 2;
	const int ACTION_STATUS_PAUSE			= 3;
	const int ACTION_STATUS_RESUME			= 4;
	const int ACTION_STATUS_COMPLETE		= 5;
	const int ACTION_STATUS_FAIL			= 6;
}